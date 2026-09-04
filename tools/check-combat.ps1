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

    Exits 0 only if every check passes.

.PARAMETER Quiet
    Print only the summary table, not each harness's own output.

.PARAMETER NoRefresh
    Skip the regeneration stage and check whatever is on disk. Use it to reproduce a
    failure exactly, not as the normal way to run this.

.EXAMPLE
    powershell -File tools\check-combat.ps1
    powershell -File tools\check-combat.ps1 -Quiet
#>
[CmdletBinding()]
param(
    [switch]$Quiet,
    [switch]$NoRefresh
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root

# JDK 21 specifically. A jre1.8 also exists on this machine and picking it up produces
# failures that look like source errors.
$jdk = 'C:\Program Files\Java\jdk-21'
if (-not (Test-Path $jdk)) {
    $jdk = $env:JAVA_HOME
}
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
if (-not (Test-Path $javac)) {
    Write-Host "no javac at $javac - set JAVA_HOME to a JDK 21" -ForegroundColor Red
    Pop-Location
    exit 2
}

$out = Join-Path $env:TEMP ('combatcheck-' + [System.Guid]::NewGuid().ToString('N'))
$results = @()

function Add-Result($name, $ok, $detail) {
    $script:results += [pscustomobject]@{ Check = $name; Passed = $ok; Detail = $detail }
}

function Invoke-JavaCheck($name, $sources, $mainClass, $sourcePath) {
    $dir = Join-Path $out $mainClass
    $null = New-Item -ItemType Directory -Force -Path $dir
    # A check that loads the data pack needs org.json on the source path. Everything else is
    # compiled from an explicit, minimal list, which is what keeps ADR-0002's seam honest.
    if ($sourcePath) {
        $compile = & $javac -nowarn -d $dir -sourcepath $sourcePath $sources 2>&1
    } else {
        $compile = & $javac -d $dir $sources 2>&1
    }
    if ($LASTEXITCODE -ne 0) {
        if (-not $Quiet) { $compile | ForEach-Object { Write-Host "    $_" } }
        Add-Result $name $false 'did not compile'
        return
    }
    # The pack check also exercises the CLASSPATH loaders, which is how the running client
    # reads its data - build.xml copies these same files in beside the classes. Staging them
    # here means a missing copy step fails a check instead of failing silently in a fight.
    $data = Join-Path $dir 'haven\combat\data'
    $null = New-Item -ItemType Directory -Force -Path $data
    Copy-Item (Join-Path $root 'data\combat\*.json') $data -Force -ErrorAction SilentlyContinue
    $run = & $java -cp $dir $mainClass 2>&1
    $ok = ($LASTEXITCODE -eq 0)
    if (-not $Quiet) { $run | ForEach-Object { Write-Host "    $_" } }
    $last = ($run | Select-Object -Last 1)
    Add-Result $name $ok "$last"
}

function Invoke-PyCheck($name, $script) {
    $run = & python $script 2>&1
    $ok = ($LASTEXITCODE -eq 0)
    if (-not $Quiet) { $run | ForEach-Object { Write-Host "    $_" } }
    $last = ($run | Select-Object -Last 1)
    Add-Result $name $ok "$last"
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
        'data\combat\weapons_seen.json'
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
    'src\haven\combat\Sim.java'
)

Write-Host "`n== the log schema" -ForegroundColor Cyan
Invoke-JavaCheck 'CombatLogCheck' (@('src\haven\combat\log\JsonObj.java',
    'src\haven\combat\log\Openings.java', 'src\haven\combat\log\CombatEvent.java',
    'src\haven\combat\log\CombatLogWriter.java', 'tools\CombatLogCheck.java')) 'CombatLogCheck'

Write-Host "`n== the model's arithmetic" -ForegroundColor Cyan
Invoke-JavaCheck 'CombatFormulaCheck' (@('src\haven\combat\Formulas.java',
    'tools\CombatFormulaCheck.java')) 'CombatFormulaCheck'

Write-Host "`n== the state machine" -ForegroundColor Cyan
Invoke-JavaCheck 'CombatSimCheck' ($model + @('tools\CombatSimCheck.java')) 'CombatSimCheck'

Write-Host "`n== the plan search" -ForegroundColor Cyan
Invoke-JavaCheck 'CombatOptimizerCheck' ($model + @('src\haven\combat\FoeModel.java',
    'src\haven\combat\Optimizer.java', 'tools\CombatOptimizerCheck.java')) 'CombatOptimizerCheck'

Write-Host "`n== the data pack, loaded the way the bot will load it" -ForegroundColor Cyan
Invoke-JavaCheck 'CombatPackCheck' ($model + @('src\haven\combat\data\Pack.java',
    'tools\CombatPackCheck.java')) 'CombatPackCheck' 'src'

Write-Host "`n== the Python follower, against the golden vectors" -ForegroundColor Cyan
Invoke-PyCheck 'model_check.py' 'tools\combat\model_check.py'

Write-Host "`n== the golden vectors match the Java they were generated from" -ForegroundColor Cyan
$vdir = Join-Path $out 'vecgen'
$null = New-Item -ItemType Directory -Force -Path $vdir
$vcompile = & $javac -nowarn -d $vdir src\haven\combat\Formulas.java src\haven\combat\log\JsonObj.java tools\CombatVectorGen.java 2>&1
if ($LASTEXITCODE -ne 0) {
    if (-not $Quiet) { $vcompile | ForEach-Object { Write-Host "    $_" } }
    Add-Result 'golden-vectors-fresh' $false 'did not compile'
} else {
    # Regeneration writes only the temporary copy: the checked-in file is never touched here.
    $vfresh = Join-Path $vdir 'golden-vectors.json'
    $vrun = & $java -cp $vdir CombatVectorGen $vfresh 2>&1
    if (-not $Quiet) { $vrun | ForEach-Object { Write-Host "    $_" } }
    # Text comparison with carriage returns stripped: a CRLF checkout of the JSON must not
    # fail a check about the model.
    $checkedIn = [System.IO.File]::ReadAllText((Join-Path $root 'data\combat\golden-vectors.json')).Replace("`r", "")
    $freshGen = [System.IO.File]::ReadAllText($vfresh).Replace("`r", "")
    if ($checkedIn -ceq $freshGen) {
        Add-Result 'golden-vectors-fresh' $true 'regeneration matches the checked-in file'
    } else {
        Add-Result 'golden-vectors-fresh' $false 'Formulas.java changed without regenerating golden-vectors.json - run CombatVectorGen and review the diff'
    }
}

Write-Host "`n== what a log is allowed to measure" -ForegroundColor Cyan
Invoke-PyCheck 'fightlog_check.py' 'tools\combat\fightlog_check.py'

Write-Host "`n== the estimators" -ForegroundColor Cyan
Invoke-PyCheck 'estimate_check.py' 'tools\combat\estimate_check.py'

Write-Host "`n== every logged fight, replayed through the model" -ForegroundColor Cyan
Invoke-PyCheck 'replay.py' 'tools\combat\replay.py'

Write-Host "`n== which fight would settle something" -ForegroundColor Cyan
Invoke-PyCheck 'experiment_check.py' 'tools\combat\experiment_check.py'

Write-Host "`n== the wiki data pack" -ForegroundColor Cyan
Invoke-PyCheck 'datapack_check.py' 'tools\combat\datapack_check.py'

Write-Host "`n== the client still builds" -ForegroundColor Cyan
$ant = 'C:\ant\apache-ant-1.10.17\bin\ant.bat'
if (Test-Path $ant) {
    $build = & $ant jar 2>&1
    $ok = ($build | Select-String -Quiet 'BUILD SUCCESSFUL')
    if (-not $Quiet) { $build | Select-String 'error|BUILD' | ForEach-Object { Write-Host "    $_" } }
    Add-Result 'ant jar' $ok (($build | Select-String 'BUILD' | Select-Object -Last 1))
} else {
    Add-Result 'ant jar' $false "no ant at $ant"
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
