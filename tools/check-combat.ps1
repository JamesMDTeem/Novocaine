<#
.SYNOPSIS
    Runs every check in the combat system and reports which passed.

.DESCRIPTION
    The combat work is spread across two languages and a dozen check harnesses, each with
    its own javac incantation buried in a file header. Retyping those is how a check
    stops being run, so this runs all of them and prints one table.

    Each Java harness is compiled on its own, from the smallest set of sources it needs.
    That is not just tidiness: haven.combat is supposed to import nothing from haven, and
    a harness that suddenly needs the whole client to compile is the alarm that the seam
    in ADR-0002 has gone. A compile failure here is a real finding, not a build problem.

    The checks do not read each other and do not write shared files, so they run in
    parallel. The regeneration stage is different: it rewrites the very files the checks
    read, so it still runs first and alone. Afterwards up to -Jobs checks run at once (by
    default one per check, so everything independent starts immediately). Each worker
    buffers its whole output and the buffers are printed in the fixed order the checks are
    declared below, so nothing interleaves and the table and section headers read exactly
    as they did when the checks ran one at a time. -Jobs 1 runs the checks serially in
    this process, which is the old behaviour and the way to read a failure without the
    job layer in the way.

    Exits 0 only if every check passes.

.PARAMETER Quiet
    Print only the summary table, not each harness's own output.

.PARAMETER NoSync
    Skip pulling the corpus from the team server. The pull is what keeps the pool from
    going stale under the estimates built from it; skip it only when offline.

.PARAMETER NoRefresh
    Skip the regeneration stage and check whatever is on disk. Use it to reproduce a
    failure exactly, not as the normal way to run this.

.PARAMETER Jobs
    How many independent checks run at once. The default (0) means one worker per check,
    capped at the processor count. Pass 1 to run them strictly serially in this process.

.EXAMPLE
    powershell -File tools\check-combat.ps1
    powershell -File tools\check-combat.ps1 -Quiet
#>
[CmdletBinding()]
param(
    [switch]$Quiet,
    [switch]$NoSync,
    [switch]$NoRefresh,
    [int]$Jobs = 0
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root

# Fixed hash seed so every suite run iterates identically. Several estimator
# censuses (deepest_interval, wd_consensus) walk structures whose order is
# hash-dependent; without this, two runs on the same corpus can pin different
# readings and the checks below would flap. Standalone python runs outside
# this script must set PYTHONHASHSEED=0 themselves to reproduce suite numbers.
$env:PYTHONHASHSEED = '0'

# JDK 21 specifically. Older JDKs exist on some machines and picking one up produces
# failures that look like source errors. Resolved in order so the script does not depend
# on this machine's install layout: the historical path, then JAVA_HOME, then whatever
# javac is on PATH, then the usual vendor install directories. Every candidate must hold
# bin\javac.exe, which is what separates a JDK from a JRE.
function Find-JdkHome {
    $cands = @('C:\Program Files\Java\jdk-21')
    if ($env:JAVA_HOME) { $cands += $env:JAVA_HOME }
    $onPath = Get-Command javac -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandType -eq 'Application' } | Select-Object -First 1
    if ($onPath) { $cands += (Split-Path (Split-Path $onPath.Source -Parent) -Parent) }
    foreach ($dir in @((Join-Path $env:ProgramFiles 'Java'),
                       (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
                       (Join-Path $env:ProgramFiles 'Microsoft'),
                       (Join-Path $env:ProgramFiles 'Amazon Corretto'))) {
        if (-not $dir -or -not (Test-Path -LiteralPath $dir)) { continue }
        $cands += @(Get-ChildItem -LiteralPath $dir -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'jdk[-.]?21' } |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName })
    }
    foreach ($c in $cands) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c 'bin\javac.exe'))) { return $c }
    }
    return $null
}

$jdk = Find-JdkHome
if (-not $jdk) {
    Write-Host 'no JDK 21 found - looked at JAVA_HOME, javac on PATH, and the usual install' -ForegroundColor Red
    Write-Host 'directories; install a JDK 21 or set JAVA_HOME to one' -ForegroundColor Red
    Pop-Location
    exit 2
}
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'

# Ant. Not on PATH by default on most machines, and this script used to name the C:\ant
# junction outright. Resolved in order - ANT_HOME, PATH, the historical path, then common
# install directories - and whichever was chosen is printed by the build check, so the
# output says which ant ran. A null here becomes a reported failed check, not a crash.
function Find-AntBat {
    $cands = @()
    if ($env:ANT_HOME) { $cands += (Join-Path $env:ANT_HOME 'bin\ant.bat') }
    $onPath = Get-Command ant -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandType -eq 'Application' -and
                       ($_.Extension -eq '.bat' -or $_.Extension -eq '.cmd') } |
        Select-Object -First 1
    if ($onPath) { $cands += $onPath.Source }
    $cands += 'C:\ant\apache-ant-1.10.17\bin\ant.bat'
    $globs = @('C:\ant\apache-ant-*\bin\ant.bat', 'C:\apache-ant\bin\ant.bat')
    if ($env:ProgramFiles) { $globs += (Join-Path $env:ProgramFiles 'apache-ant*\bin\ant.bat') }
    if (${env:ProgramFiles(x86)}) { $globs += (Join-Path ${env:ProgramFiles(x86)} 'apache-ant*\bin\ant.bat') }
    if ($env:ProgramData) {
        $globs += (Join-Path $env:ProgramData 'chocolatey\lib\ant\tools\apache-ant-*\bin\ant.bat')
        $globs += (Join-Path $env:ProgramData 'chocolatey\bin\ant.bat')
    }
    if ($env:USERPROFILE) { $globs += (Join-Path $env:USERPROFILE 'scoop\apps\ant\current\bin\ant.bat') }
    foreach ($g in $globs) {
        $hit = Get-Item -Path $g -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($hit) { $cands += $hit.FullName }
    }
    foreach ($c in $cands) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    return $null
}

$antBat = Find-AntBat

$out = Join-Path $env:TEMP ('combatcheck-' + [System.Guid]::NewGuid().ToString('N'))
$results = @()

# ---------------------------------------------------------------------------------
# ONE CHECK, ONE WORKER.
#
# This is the whole per-check body: compile (if it is a Java harness), run it, keep the
# output, and return a result object. It is used two ways. For -Jobs 1 it is called
# directly in this process, which is the old strictly-serial path. For the parallel path
# it is the body of a Start-Job, so it must be self-contained - it may only rely on the
# values handed to it through $Spec (which check) and $Ctx (where the JDK, ant, repo and
# temp output live), because a Start-Job body runs in a fresh process with no parent vars.
#
# Output is buffered into $lines rather than printed here. That is what lets the parallel
# run print in declaration order instead of completion order, and it is also why the
# compile/run logic below is otherwise a straight copy of what the serial runner did.
$executor = {
    param($Spec, $Ctx)

    # The runner sets this before any check starts, and Start-Job children inherit the
    # parent's environment, but a worker setting it itself means a job can never run
    # without the seed that keeps the hash-order-dependent censuses reproducible.
    $env:PYTHONHASHSEED = '0'
    $root = $Ctx.Root
    Set-Location $root
    $javac = Join-Path $Ctx.Jdk 'bin\javac.exe'
    $java = Join-Path $Ctx.Jdk 'bin\java.exe'

    $lines = New-Object System.Collections.ArrayList
    $ok = $false
    $detail = ''

    if ($Spec.Kind -eq 'java') {
        $sources = @($Spec.Sources)
        # Every harness compiles into its own directory under the run's GUID-rooted temp
        # tree. Unique per check is what makes parallel javac safe.
        $dir = Join-Path $Ctx.Out $Spec.MainClass
        $null = New-Item -ItemType Directory -Force -Path $dir
        # A check that loads the data pack needs org.json on the source path. Everything
        # else is compiled from an explicit, minimal list, which is what keeps ADR-0002's
        # seam honest.
        if ($Spec.SourcePath) {
            $compile = & $javac -nowarn -d $dir -sourcepath $Spec.SourcePath $sources 2>&1
        } else {
            $compile = & $javac -d $dir $sources 2>&1
        }
        if ($LASTEXITCODE -ne 0) {
            foreach ($l in $compile) { [void]$lines.Add([string]$l) }
            $detail = 'did not compile'
        } else {
            # The pack check also exercises the CLASSPATH loaders, which is how the running
            # client reads its data - build.xml copies these same files in beside the
            # classes. Staging them here means a missing copy step fails a check instead of
            # failing silently in a fight.
            $data = Join-Path $dir 'haven\combat\data'
            $null = New-Item -ItemType Directory -Force -Path $data
            Copy-Item (Join-Path $root 'data\combat\*.json') $data -Force -ErrorAction SilentlyContinue
            $run = & $java -cp $dir $Spec.MainClass 2>&1
            $ok = ($LASTEXITCODE -eq 0)
            foreach ($l in $run) { [void]$lines.Add([string]$l) }
            $detail = "$($run | Select-Object -Last 1)"
        }
    }
    elseif ($Spec.Kind -eq 'py') {
        $run = & python $Spec.Script 2>&1
        $ok = ($LASTEXITCODE -eq 0)
        foreach ($l in $run) { [void]$lines.Add([string]$l) }
        $detail = "$($run | Select-Object -Last 1)"
    }
    elseif ($Spec.Kind -eq 'vec') {
        $vdir = Join-Path $Ctx.Out 'vecgen'
        $null = New-Item -ItemType Directory -Force -Path $vdir
        $vcompile = & $javac -nowarn -d $vdir src\haven\combat\Formulas.java src\haven\combat\log\JsonObj.java tools\CombatVectorGen.java 2>&1
        if ($LASTEXITCODE -ne 0) {
            foreach ($l in $vcompile) { [void]$lines.Add([string]$l) }
            $detail = 'did not compile'
        } else {
            # Regeneration writes only the temporary copy: the checked-in file is never
            # touched here.
            $vfresh = Join-Path $vdir 'golden-vectors.json'
            $vrun = & $java -cp $vdir CombatVectorGen $vfresh 2>&1
            foreach ($l in $vrun) { [void]$lines.Add([string]$l) }
            # Text comparison with carriage returns stripped: a CRLF checkout of the JSON
            # must not fail a check about the model.
            $checkedIn = [System.IO.File]::ReadAllText((Join-Path $root 'data\combat\golden-vectors.json')).Replace("`r", "")
            $freshGen = [System.IO.File]::ReadAllText($vfresh).Replace("`r", "")
            if ($checkedIn -ceq $freshGen) {
                $ok = $true
                $detail = 'regeneration matches the checked-in file'
            } else {
                $detail = 'Formulas.java changed without regenerating golden-vectors.json - run CombatVectorGen and review the diff'
            }
        }
    }
    elseif ($Spec.Kind -eq 'seam') {
        # ADR-0002's seam, compiled rather than asserted.
        #
        # haven.combat may not depend on the client, and every other harness enforces that
        # by compiling from an explicit, minimal source list - a stray `import haven.X`
        # then has nowhere to resolve from and the compile fails. Pack.java was the one
        # file outside that discipline: it needs org.json, so its check passes
        # `-sourcepath src`, and a source path that reaches org.json reaches ALL of haven
        # too. The file that does the JSON join - the likeliest place for a client import
        # to creep in - was the one place the seam was not being held.
        #
        # So org.json is compiled on its own first and handed over as a CLASSPATH, and the
        # model plus Pack.java are compiled from an explicit list with no source path at
        # all. The compile IS the reading: it succeeds only while nothing in these files
        # imports haven outside haven.combat. Negative test, run by hand when this was
        # written: adding `import haven.Widget;` to Pack.java fails it with "cannot find
        # symbol", while every other check and `ant jar` stay green.
        $jdir = Join-Path $Ctx.Out 'seam-json'
        $pdir = Join-Path $Ctx.Out 'seam-pack'
        $null = New-Item -ItemType Directory -Force -Path $jdir
        $null = New-Item -ItemType Directory -Force -Path $pdir
        $jsrc = @(Get-ChildItem (Join-Path $root 'src\org\json\*.java') |
                  ForEach-Object { $_.FullName })
        $jc = & $javac -nowarn -d $jdir $jsrc 2>&1
        if ($LASTEXITCODE -ne 0) {
            foreach ($l in $jc) { [void]$lines.Add([string]$l) }
            $detail = 'org.json did not compile'
        } else {
            $pc = & $javac -nowarn -d $pdir -cp $jdir $Spec.Sources 2>&1
            $ok = ($LASTEXITCODE -eq 0)
            foreach ($l in $pc) { [void]$lines.Add([string]$l) }
            if ($ok) {
                $detail = 'the pack compiles with no source path: nothing reaches into haven'
            } else {
                $detail = 'the pack needs something outside haven.combat - ADR-0002 seam broken'
            }
        }
    }
    elseif ($Spec.Kind -eq 'ant') {
        $ant = $Ctx.Ant
        if ($ant) {
            [void]$lines.Add("using $ant")
            $build = & $ant jar 2>&1
            $ok = ($build | Select-String -Quiet 'BUILD SUCCESSFUL')
            foreach ($l in ($build | Select-String 'error|BUILD')) { [void]$lines.Add([string]$l) }
            $detail = "$($build | Select-String 'BUILD' | Select-Object -Last 1)"
        } else {
            $detail = 'no ant found in ANT_HOME, on PATH, at C:\ant, or in common installs'
        }
    }

    [pscustomobject]@{ Name = $Spec.Name; Passed = $ok; Detail = $detail; Output = @($lines) }
}

# ---------------------------------------------------------------------------------
# PULL BEFORE REGENERATING. Everything below rebuilds the data pack from the pooled
# corpus, so a corpus two days behind the server produces estimates two days behind
# the server - and nothing said so. The pool sat stale for two days while every
# check passed, because a pool nobody has refreshed is internally identical to one
# nobody has added to. pool_check.py now fails on the age of the last pull; this is the
# other half, which makes that stamp fresh in normal use so the check only fires when
# the sync has genuinely stopped working.
#
# NEVER FATAL. A laptop off the network, or away from the machine hosting the server,
# must still be able to run the checks - it just runs them against the pool it has, and
# is told so. Two routes: HTTP when the endpoint is configured, which works anywhere,
# and the server's own database when it is not, which works only on the host.
if (-not $NoSync) {
    Write-Host "`n== pulling the corpus from the team server" -ForegroundColor Cyan
    if ($env:HHM_COMBATLOG_ENDPOINT) {
        $sync = & python 'tools\combat\sync_pool.py' 2>&1
        $how = 'over HTTP'
    } else {
        $sync = & python 'tools\combat\sync_pool.py' '--from-db' 2>&1
        $how = 'from the server database'
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  sync failed $how - checking against the pool already on disk" -ForegroundColor Yellow
        Write-Host "  set HHM_COMBATLOG_ENDPOINT to pull from a machine that is not the server host" -ForegroundColor Yellow
        if (-not $Quiet) { $sync | Select-Object -Last 6 | ForEach-Object { Write-Host "    $_" } }
    } else {
        $sync | Where-Object { $_ -match 'wrote|to fetch' } | ForEach-Object { Write-Host "  $_" }
    }
}


# ---------------------------------------------------------------------------------
# REGENERATE FIRST, THEN CHECK.
#
# Half the data under data/combat is derived - the deck sheet from the client's own
# dumps, the opponent pack and the weapon readings from the logged corpus - and it is
# checked in, because the client is built with it and a running client has to carry the
# pack it was built with. Which makes every one of those files a thing that can be stale.
#
# It went stale twice in one session. A change to the estimator left opponents.json on
# disk describing the previous rule, and the check that reads it compared a fresh
# computation against an old file and failed - a red check reporting nothing but the
# order two commands were run in. Then a change to an attribution gate did it again.
#
# So the derived files are rebuilt here before anything reads them, and WHAT CHANGED IS
# PRINTED. Regenerating silently would trade a false failure for a false pass, which is
# the worse of the two: the point is not that the checks go green, it is that a change to
# the estimator visibly moves the pack.
#
# This stage is deliberately NOT one of the parallel jobs: it writes the files every
# check reads, so it must finish before any of them starts.
#
# Not regenerated: anything scraped from the wiki (build_datapack.py, which needs the
# network), and the golden vectors, which have their own freshness check further down
# that regenerates to a temporary file and diffs - the right pattern where the checked-in
# copy is the thing under test.
if (-not $NoRefresh) {
    Write-Host "`n== regenerating what is derived" -ForegroundColor Cyan
    $derived = @(
        'data\combat\moves_sheet.json',
        'data\combat\moves_ingame.json',
        'data\combat\opponents.json',
        'data\combat\weapons_seen.json',
        # Also written by estimate.py --write-pack. Both moved silently while this list
        # watched only the four above (2026-09-11 audit: animal_moves_measured.json lost
        # observations to the third-party veto and the stage still said "nothing moved").
        'data\combat\characters.json',
        'data\combat\animal_moves_measured.json'
    )
    $before = @{}
    foreach ($f in $derived) {
        $path = Join-Path $root $f
        if (Test-Path $path) { $before[$f] = (Get-FileHash $path -Algorithm SHA256).Hash }
    }
    $gen = & python 'tools\combat\parse_deck.py' 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  parse_deck.py failed - the deck sheet is whatever was on disk" -ForegroundColor Yellow
        if (-not $Quiet) { $gen | Select-Object -Last 6 | ForEach-Object { Write-Host "    $_" } }
    }
    $gen = & python 'tools\combat\estimate.py' '--write-pack' 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  estimate.py failed - the pack is whatever was on disk" -ForegroundColor Red
        if (-not $Quiet) { $gen | Select-Object -Last 20 | ForEach-Object { Write-Host "    $_" } }
    }
    $changed = @()
    foreach ($f in $derived) {
        $path = Join-Path $root $f
        $now = if (Test-Path $path) { (Get-FileHash $path -Algorithm SHA256).Hash } else { $null }
        if ($before[$f] -ne $now) { $changed += $f }
    }
    if ($changed.Count -gt 0) {
        Write-Host "  REGENERATED, and the contents moved:" -ForegroundColor Yellow
        $changed | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
        Write-Host "  Everything below is checked against the new files. Commit them with"
        Write-Host "  whatever changed the estimator, or the next run starts stale again."
    } else {
        Write-Host "  up to date - nothing derived moved"
    }
}

$model = @(
    'src\haven\combat\Formulas.java',
    'src\haven\combat\Move.java',
    'src\haven\combat\Combatant.java',
    # The opponent's own cards, which FoeModel now plays instead of an average.
    'src\haven\combat\BeastMove.java',
    'src\haven\combat\Repertoire.java',
    'src\haven\combat\Sim.java'
)

# ---------------------------------------------------------------------------------
# THE CHECK LIST.
#
# Declared once, in order. Each entry carries the section header that is printed above
# its output and everything the worker needs to run it. The parallel scheduler reads this
# array; the report prints from the same array, so the order here is the order of the
# table and of the output no matter which worker finishes first. CombatAudit has no
# section header on purpose - it is the audit that sits under the pack check's banner.
$specs = @(
    [pscustomobject]@{ Kind = 'java'; Name = 'CombatLogCheck'; Section = 'the log schema'; MainClass = 'CombatLogCheck'; SourcePath = $null;
        Sources = @('src\haven\combat\log\JsonObj.java',
            'src\haven\combat\log\Openings.java', 'src\haven\combat\log\CombatEvent.java',
            'src\haven\combat\log\CombatLogWriter.java', 'tools\CombatLogCheck.java') }
    [pscustomobject]@{ Kind = 'java'; Name = 'CombatFormulaCheck'; Section = "the model's arithmetic"; MainClass = 'CombatFormulaCheck'; SourcePath = $null;
        Sources = @('src\haven\combat\Formulas.java', 'tools\CombatFormulaCheck.java') }
    [pscustomobject]@{ Kind = 'java'; Name = 'CombatSimCheck'; Section = 'the state machine'; MainClass = 'CombatSimCheck'; SourcePath = $null;
        Sources = ($model + @('src\haven\combat\FoeModel.java', 'src\haven\combat\Duel.java', 'tools\CombatSimCheck.java')) }
    [pscustomobject]@{ Kind = 'java'; Name = 'CombatOptimizerCheck'; Section = 'the plan search'; MainClass = 'CombatOptimizerCheck'; SourcePath = $null;
        Sources = ($model + @('src\haven\combat\FoeModel.java', 'src\haven\combat\Optimizer.java',
            'src\haven\combat\Advisor.java', 'tools\CombatOptimizerCheck.java')) }
    [pscustomobject]@{ Kind = 'java'; Name = 'CombatPackCheck'; Section = 'the data pack, loaded the way the bot will load it'; MainClass = 'CombatPackCheck'; SourcePath = 'src';
        Sources = ($model + @('src\haven\combat\data\Pack.java', 'tools\CombatPackCheck.java')) }
    # The audit: not whether a number is right, but whether anything reads it at all.
    # Both of the last two bugs were a mechanic parsed, stored, shipped and consumed by
    # nothing - invisible in the source, because the source looked finished.
    [pscustomobject]@{ Kind = 'java'; Name = 'CombatAudit'; Section = $null; MainClass = 'CombatAudit'; SourcePath = 'src';
        Sources = ($model + @('src\haven\combat\FoeModel.java', 'src\haven\combat\Duel.java',
            'src\haven\combat\Optimizer.java', 'src\haven\combat\Advisor.java', 'tools\CombatAudit.java')) }
    [pscustomobject]@{ Kind = 'py'; Name = 'model_check.py'; Section = 'the Python follower, against the golden vectors'; Script = 'tools\combat\model_check.py' }
    [pscustomobject]@{ Kind = 'vec'; Name = 'golden-vectors-fresh'; Section = 'the golden vectors match the Java they were generated from' }
    [pscustomobject]@{ Kind = 'py'; Name = 'fightlog_check.py'; Section = 'what a log is allowed to measure'; Script = 'tools\combat\fightlog_check.py' }
    [pscustomobject]@{ Kind = 'py'; Name = 'pool_check.py'; Section = 'the pooled corpus on disk'; Script = 'tools\combat\pool_check.py' }
    [pscustomobject]@{ Kind = 'py'; Name = 'estimate_check.py'; Section = 'the estimators'; Script = 'tools\combat\estimate_check.py' }
    [pscustomobject]@{ Kind = 'py'; Name = 'replay.py'; Section = 'every logged fight, replayed through the model'; Script = 'tools\combat\replay.py' }
    [pscustomobject]@{ Kind = 'py'; Name = 'experiment_check.py'; Section = 'which fight would settle something'; Script = 'tools\combat\experiment_check.py' }
    [pscustomobject]@{ Kind = 'py'; Name = 'datapack_check.py'; Section = 'the wiki data pack'; Script = 'tools\combat\datapack_check.py' }
    [pscustomobject]@{ Kind = 'py'; Name = 'attribution_check.py'; Section = 'who threw the hit, across the joined logs'; Script = 'tools\combat\attribution_check.py' }
    [pscustomobject]@{ Kind = 'seam'; Name = 'ADR-0002 seam'; Section = 'the pack does not reach into the client';
        Sources = ($model + @('src\haven\combat\FoeModel.java',
            'src\haven\combat\data\Pack.java')) }
    [pscustomobject]@{ Kind = 'ant'; Name = 'ant jar'; Section = 'the client still builds' }
)

$ctx = [pscustomobject]@{ Jdk = $jdk; Root = $root; Out = $out; Ant = $antBat }

# Default: one worker per check, capped at the processor count, so everything independent
# starts at once. -Jobs 1 is the serial path and does not use Start-Job at all.
$checkCount = $specs.Count
$jobs = $Jobs
if ($jobs -lt 1) {
    $jobs = [Math]::Min($checkCount, [Environment]::ProcessorCount)
}
if ($jobs -lt 1) { $jobs = 1 }

$byIndex = @{}
if ($jobs -le 1) {
    # The old strictly-serial behaviour, run in this process: no job layer, so a failure
    # can be read in the debugger and each harness sees the same environment it always did.
    for ($i = 0; $i -lt $checkCount; $i++) {
        $byIndex[$i] = & $executor $specs[$i] $ctx
    }
} else {
    # Start checks until $jobs are running, then harvest whichever finishes next. A
    # completed result is filed under its spec index, not appended, so reporting order is
    # independent of completion order.
    $running = @{}
    $next = 0
    while ($next -lt $checkCount -or $running.Count -gt 0) {
        while ($next -lt $checkCount -and $running.Count -lt $jobs) {
            $job = Start-Job -ScriptBlock $executor -ArgumentList $specs[$next], $ctx
            $running[$job.Id] = [pscustomobject]@{ Job = $job; Index = $next }
            $next = $next + 1
        }
        if ($running.Count -eq 0) { break }
        $active = @($running.Values | ForEach-Object { $_.Job })
        $done = Wait-Job -Job $active -Any
        foreach ($j in @($done)) {
            $rec = $running[$j.Id]
            $r = Receive-Job -Job $j -ErrorAction SilentlyContinue
            Remove-Job -Job $j -Force
            if ($null -eq $r) {
                # A worker that died without returning a result is a failed check, not a
                # missing row: the table must still account for every check.
                $r = [pscustomobject]@{ Name = $specs[$rec.Index].Name; Passed = $false; Detail = 'the check job produced no result'; Output = @() }
            }
            $byIndex[$rec.Index] = $r
            $null = $running.Remove($j.Id)
        }
    }
}

# Print in declaration order. Section headers go out even under -Quiet, as they always
# have; only each harness's own buffered output is suppressed.
$results = @()
for ($i = 0; $i -lt $checkCount; $i++) {
    $r = $byIndex[$i]
    if ($specs[$i].Section) {
        Write-Host "`n== $($specs[$i].Section)" -ForegroundColor Cyan
    }
    if (-not $Quiet) {
        foreach ($l in @($r.Output)) { Write-Host "    $l" }
    }
    $results += [pscustomobject]@{ Check = $r.Name; Passed = [bool]$r.Passed; Detail = $r.Detail }
}

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue

Write-Host "`n"
$results | Format-Table -AutoSize @(
    @{ Label = 'check'; Expression = { $_.Check } },
    @{ Label = ' '; Expression = { if ($_.Passed) { 'ok' } else { 'FAILED' } } },
    @{ Label = 'result'; Expression = { ($_.Detail -replace '\s+', ' ').Trim() } }
)

$failed = @($results | Where-Object { -not $_.Passed })
Pop-Location
if ($failed.Count -gt 0) {
    Write-Host ("{0} of {1} checks FAILED" -f $failed.Count, $results.Count) -ForegroundColor Red
    exit 1
}
Write-Host ("all {0} checks passed" -f $results.Count) -ForegroundColor Green
exit 0
