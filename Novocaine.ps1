<#
.SYNOPSIS
    The one Novocaine script: update or build, then launch.

.DESCRIPTION
    This replaces the pile of launchers the project used to carry (Play.bat,
    Play_NoConsole.bat, Play_WithSteam.bat, Play_WithSteam_NoConsole.bat, Play_ZGC.bat,
    build-and-play.ps1, play-crew.ps1 and launcher\Update.ps1). Every one of them was the
    same launch with one thing changed, and the JVM flags were copied into six files that
    then drifted apart.

    It works out for itself which of the two worlds it is in:

      * SOURCE CHECKOUT (build.xml next to it) - resolve the JDK and Ant, run the build,
        confirm the alchemy code made it into the jar, launch out of bin\.
      * INSTALLED CLIENT (hafen.jar next to it, no build.xml) - check GitHub for a newer
        release, apply it (preferring the small delta over the ~170MB full zip), verify
        every file against manifest.json, launch.

    Neither mode ever touches git. Upstream changes are pulled in deliberately, one at a
    time, through tools\merge-upstream.ps1.

    The JVM flags are NOT written out here. They are read from Play.bat, which has to keep
    existing anyway: hafen.hl names it as the Steam launcher's `command-file`, and that is
    where the HL launcher reads the --add-exports and -D properties from. One file holds
    the flags, two readers agree on them. Steam Play (HL path via hafen.hl) is ZGC
    by default with heap auto-scale 6144/8192 when headroom allows, without needing
    this wrapper or -ZGC flag - Play.bat and hafen.hl jvm-arg carry the flags.

.PARAMETER Count
    How many clients to start. One Haven client is one character, so a crew of eight is
    eight JVMs. Default 1.

.PARAMETER StaggerSeconds
    Seconds between launches when Count > 1. Eight JVMs creating GL contexts in the same
    instant is how one of them fails on a driver timeout; a few seconds apart costs
    nothing. Default 3.

.PARAMETER Console
    Attach a console window and wait for the client to exit. The default is javaw.exe: no
    console, and this script returns as soon as the game is up.

.PARAMETER NoZGC
    Opt out of generational ZGC and use G1 instead. ZGC is now the default.

    Measured on JDK 21.0.9 over a 45s client session (login screen, GC logging on):
      G1   106 pauses, 436.4ms total, max 12.73ms, 6 pauses over 10ms
      ZGC   90 pauses,   0.8ms total, max  0.02ms, 0 pauses over 5ms
    A 60fps frame is 16.7ms, so G1 worst-case ate 76% of one frame. The trade is
    footprint: ZGC floated to 3632M before collecting where G1 peaked near 1515M.
    With -Count N each ZGC client holds a larger floating heap, so budget
    accordingly on 16G boxes or when running a crew.

    -XX:+ZGenerational is required on JDK 21 to get the generational collector. It was
    made the default in 23 and REMOVED in 24, where passing it is a fatal "Unrecognized VM
    option". -XX:+IgnoreUnrecognizedVMOptions (already in Play.bat) makes the pair correct
    on both. Verified against 21.0.9 and 26.0.1. Alias -G1 is accepted.

.PARAMETER G1
    Alias for -NoZGC: use G1 instead of the default ZGC.

.PARAMETER Steam
    Launch with -DrunningThroughSteam=true. Detected automatically for a Workshop install
    (BUILD-INFO.txt says Channel: steam), so this is only for forcing it by hand.

.PARAMETER NoBuild
    Source checkout: skip the build and launch what is already staged in bin\.

.PARAMETER NoUpdate
    Installed client: skip the update check and just launch.

.PARAMETER NoLaunch
    Do the build or the update, then stop. This is the build gate - `.\Novocaine.ps1
    -NoLaunch` is the real typecheck for this tree.

.PARAMETER Check
    Installed client: report whether an update exists and exit without downloading it.

.PARAMETER Force
    Installed client: re-apply the latest release even if the installed version already
    matches, to repair an install whose files have been damaged.

.PARAMETER Full
    Installed client: skip the delta and download the complete client.

.PARAMETER DryRun
    Print the command line each client would be started with, and start nothing. The flags
    are parsed out of Play.bat, so this is how you check that parse without putting eight
    game windows on screen.

.EXAMPLE
    .\Novocaine.ps1                      # update-or-build, then play (ZGC by default)
    .\Novocaine.ps1 -NoLaunch            # build only (the typecheck gate)
    .\Novocaine.ps1 -Count 8             # build once, launch a crew of eight
    .\Novocaine.ps1 -Count 2 -NoBuild    # two more clients against the build you have
    .\Novocaine.ps1 -Console             # with a console to read GC logs in
    .\Novocaine.ps1 -NoZGC -Console      # G1 instead of ZGC, with console
    .\Novocaine.ps1 -Check               # is there a newer release?
#>

[CmdletBinding()]
param(
    [int]$Count = 1,
    [int]$StaggerSeconds = 3,
    [switch]$Console,
    [switch]$NoZGC,
    [switch]$G1,
    [switch]$ZGC,
    [switch]$Steam,
    [switch]$NoBuild,
    [switch]$NoUpdate,
    [switch]$NoLaunch,
    [switch]$Check,
    [switch]$Force,
    [switch]$Full,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # PS 5.1's progress bar makes web calls crawl

# Novocaine.bat runs a COPY of this script out of %TEMP%, precisely so an update is free to
# overwrite this folder - including this file - while it is running. Trust the variable the
# bat passes rather than $PSScriptRoot when it is there.
$root = $PSScriptRoot
if ($env:NOVOCAINE_HOME) { $root = $env:NOVOCAINE_HOME }

function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    $m" -ForegroundColor Yellow }
function Die($m)  {
    Write-Host "`n!! $m" -ForegroundColor Red
    # Launched by double-clicking Novocaine.bat there is no shell to read the error in, so
    # hold the window open. From a PowerShell prompt the error is already on screen.
    if ($env:NOVOCAINE_PAUSE_ON_ERROR) {
        Write-Host "`nPress Enter to close." -ForegroundColor Yellow
        [void](Read-Host)
    }
    exit 1
}

if ($Count -lt 1) { Die "Count must be at least 1 (got $Count)." }

$isSource = Test-Path -LiteralPath (Join-Path $root 'build.xml')
if (-not $isSource -and -not (Test-Path -LiteralPath (Join-Path $root 'hafen.jar'))) {
    Die "$root is neither a Novocaine checkout (no build.xml) nor an install (no hafen.jar)."
}

# ============================================================================
#  Launching
# ============================================================================

# Play.bat is `"%JAVA%" <flags> -jar hafen.jar`. Take everything after the executable and
# hand it straight to the JVM, so the flags have exactly one home - one that the Steam HL
# launcher reads too (hafen.hl: `command-file Play.bat`).
#
# Steam Play (HL path) is ZGC by default: hafen.hl carries jvm-arg -XX:+UseZGC
# -XX:+ZGenerational with -XX:+IgnoreUnrecognizedVMOptions first for JDK<25 compat,
# so hitting Play in Steam needs no wrapper or -ZGC flag. The single home for
# -Xmx/-XX remains Play.bat line 31; hafen.hl duplicates ZGC via jvm-arg because
# HL ignores -XX in command-file.

# Heap auto-scaling: floor 4096m always; bump to 6144m if TotalRAM >=16G with
# headroom, to 8192m if >=24G (or headroom allows) with headroom. Headroom =
# TotalRAM - (Count * candidate) - 4G OS reserve must remain >=0. TotalRAM via
# WMI Win32_ComputerSystem.TotalPhysicalMemory. Play.bat stays at 4096m as the
# static fallback; this override is applied dynamically at launch (regex replace
# -Xmx\d+m). HL launcher reads hafen.hl `heap-size` separately; when launching
# via this wrapper the JVM -Xmx here wins, so hafen.hl can stay at 4096 as its
# own fallback (Steam HL path without the wrapper uses the HL value). To make
# Steam auto-scale even without the wrapper, this script also patches hafen.hl
# heap-size to the scaled value whenever it runs, so a subsequent Steam launch
# inherits the scaling.
function Get-TotalPhysicalMemoryBytes {
    try {
        $cs = Get-CimInstance -ClassName Win32_ComputerSystem -ErrorAction Stop
        if ($cs.TotalPhysicalMemory -and $cs.TotalPhysicalMemory -gt 0) { return [long]$cs.TotalPhysicalMemory }
    } catch {}
    try {
        $cs2 = Get-WmiObject -Class Win32_ComputerSystem -ErrorAction Stop
        if ($cs2.TotalPhysicalMemory) { return [long]$cs2.TotalPhysicalMemory }
    } catch {}
    return 0
}

function Get-ScaledHeapMb {
    param([long]$totalBytes, [int]$clientCount)
    $floor = 4096
    $mid = 6144
    $high = 8192
    $osReserveMb = 4096
    if ($totalBytes -le 0) { return $floor }
    $totalMb = [math]::Floor($totalBytes / 1MB)
    $hasHeadroom = {
        param($candidate)
        return $totalMb -ge ($candidate * $clientCount + $osReserveMb)
    }
    $hasMid = & $hasHeadroom $mid
    $hasHigh = & $hasHeadroom $high
    # Headroom is the hard gate; thresholds are the soft gate. Floor always.
    # 6144 requires Total >=16G && headroom. 8192 requires Total >=24G && headroom
    # (spec's "or headroom" clause is implemented as headroom being mandatory, not
    # as an alternative to the 24G threshold, to avoid overcommitting 16G boxes).
    if ($totalMb -ge 24576 -and $hasHigh) {
        return $high
    }
    if ($totalMb -ge 16384 -and $hasMid) {
        return $mid
    }
    return $floor
}

function Get-JvmArgs($dir) {
    $playBat = Join-Path $dir 'Play.bat'
    if (-not (Test-Path -LiteralPath $playBat)) { Die "No Play.bat in $dir - the JVM flags live there." }
    $line = Get-Content -LiteralPath $playBat |
        Where-Object { $_ -match 'hafen\.jar' -and $_ -notmatch '^\s*(rem\b|@|::)' } |
        Select-Object -First 1
    if (-not $line) { Die "Couldn't find the java command line in $playBat." }
    $a = $line.Trim() -replace '^start\s+"[^"]*"\s+', ''
    $a = $a -replace '^\s*"?%JAVA%"?\s+', ''
    $a = $a -replace '^\s*"?javaw?(\.exe)?"?\s+', ''
    $a = $a.Trim()
    if (-not $a) { Die "Parsed an empty argument list out of $playBat." }

    if ($Steam) { $a = $a -replace '-DrunningThroughSteam=false', '-DrunningThroughSteam=true' }
    # ZGC is the default (opt-out via -NoZGC / -G1). The repeated
    # -XX:+IgnoreUnrecognizedVMOptions is deliberate: it must come BEFORE +ZGenerational for
    # a JDK 24+ to ignore rather than reject it, and this way that holds however Play.bat
    # orders its own flags. The guard itself (-XX:+IgnoreUnrecognizedVMOptions) stays
    # first so Play.bat's standalone launch also tolerates unknown flags.
    $useZGC = -not $NoZGC -and -not $G1
    if ($useZGC) {
        if ($a -notmatch 'UseZGC') {
            $a = '-XX:+IgnoreUnrecognizedVMOptions -XX:+UseZGC -XX:+ZGenerational ' + $a
        }
    } else {
        # Opt-out: Play.bat now carries ZGC by default, so strip it for G1.
        $a = $a -replace '-XX:\+UseZGC\s*', ''
        $a = $a -replace '-XX:\+ZGenerational\s*', ''
        # Collapse any double spaces left by removal.
        $a = $a -replace '\s{2,}', ' '
    }

    # Heap auto-scaling: override -Xmx in place, preserving -Xms1024m and guard order.
    # Play.bat is the static fallback at 4096m; this is the dynamic override.
    $totalBytes = Get-TotalPhysicalMemoryBytes
    $scaledMb = Get-ScaledHeapMb -totalBytes $totalBytes -clientCount $Count
    if ($a -match '-Xmx(\d+)m') {
        $currentXmx = [int]$Matches[1]
        if ($scaledMb -ne $currentXmx) {
            $a = $a -replace '-Xmx\d+m', "-Xmx${scaledMb}m"
        }
    }

    # Keep hafen.hl heap-size in sync so Steam HL path (which ignores Play.bat -Xmx)
    # also auto-scales on the next Steam launch, even without this wrapper.
    # For source checkouts $dir is bin\ — patch only the staged copy, not the
    # repo source (hafen.hl at $root stays at 4096 floor). For installed
    # clients $dir -eq $root, so the single hafen.hl there is patched.
    try {
        $hlPath = Join-Path $dir 'hafen.hl'
        if (Test-Path -LiteralPath $hlPath) {
            $hlText = [IO.File]::ReadAllText($hlPath)
            if ($hlText -match '(?m)^heap-size\s+(\d+)') {
                $cur = [int]$Matches[1]
                if ($cur -ne $scaledMb) {
                    # Rewrite the one line and touch nothing else. An earlier version
                    # normalised the whole file to CRLF on the theory that HL required
                    # it; the checked-in hafen.hl is LF and loads fine, and rewriting
                    # every line turned a three-line change into a 104-line diff that
                    # hid what had actually been edited. Whatever endings the file
                    # arrived with are what it keeps.
                    $hlNew = $hlText -replace '(?m)^heap-size\s+\d+', "heap-size $scaledMb"
                    [IO.File]::WriteAllText($hlPath, $hlNew, [Text.UTF8Encoding]::new($false))
                }
            }
        }
    } catch {}

    return $a
}

# A bundled runtime if make-jre.ps1 put one there, otherwise whatever is on PATH.
function Get-JavaExe($dir) {
    $exe = 'javaw.exe'
    if ($Console) { $exe = 'java.exe' }
    $bundled = Join-Path $dir "jre\bin\$exe"
    if (Test-Path -LiteralPath $bundled) { return $bundled }
    $onPath = Get-Command ($exe -replace '\.exe$', '') -ErrorAction SilentlyContinue
    if (-not $onPath) {
        Warn 'No Java found on PATH.'
        Warn 'Install Eclipse Temurin JDK 21 (free, official builds of OpenJDK):'
        Warn '  https://adoptium.net/temurin/releases/?version=21'
        Warn 'During setup, tick "Set JAVA_HOME" and "Add to PATH" if the installer offers them,'
        Warn 'then close this window and run Novocaine again.'
        Die "$exe not found on PATH and no bundled jre\ in $dir."
    }
    return $onPath.Source
}

# $dir must be the working directory, not just where the jar is. alchemy-book-dump.json,
# botclaims\, botplaces.json, botmap.json and the logs are all resolved relative to it, and
# every client in a crew has to land on the SAME ones or none of the cross-process
# coordination works.
function Start-Client($dir, $n) {
    $java = Get-JavaExe $dir
    $jvmArgs = Get-JvmArgs $dir

    if ($DryRun) {
        Step 'Dry run -- nothing is being started'
        Ok "working directory: $dir"
        Ok "would run $n x: $java $jvmArgs"
        return
    }

    # -Xms is COMMITTED per process, so the floor is real even when nothing is using it.
    # The -Xmx ceiling only matters if they all fill up. A heads-up, not a limit.
    # ZGC default: measured 3632M peak vs 1515M on G1 (45s session), so a Count crew
    # with ZGC holds more floating heap; budget accordingly or use -NoZGC/-G1 to opt out.
    if (($n -gt 1) -and ($jvmArgs -match '-Xms(\d+)m')) {
        $floorGb = [math]::Round(([int]$Matches[1] * $n) / 1024.0, 1)
        Warn "$n clients reserve about $floorGb GB of heap between them before anything loads."
    }

    Step "Launching $n client(s) from $dir"
    $started = @()
    for ($i = 1; $i -le $n; $i++) {
        if ($Console) {
            # -NoNewWindow so the client's stdout lands in the shell that asked for it,
            # which is the only reason to want a console in the first place.
            $p = Start-Process -FilePath $java -ArgumentList $jvmArgs -WorkingDirectory $dir -PassThru -NoNewWindow
        } else {
            $p = Start-Process -FilePath $java -ArgumentList $jvmArgs -WorkingDirectory $dir -PassThru
        }
        $started += $p
        Ok "client $i of $n -- pid $($p.Id)"
        if (($i -lt $n) -and ($StaggerSeconds -gt 0)) { Start-Sleep -Seconds $StaggerSeconds }
    }

    if ($Console) {
        # java.exe was asked for, so the caller wants to watch it. Stay attached.
        $started | Wait-Process
    } elseif ($n -gt 1) {
        Write-Host ''
        Write-Host "    Stop them all with:  Stop-Process -Id $($started.Id -join ',')" -ForegroundColor DarkGray
        Write-Host '    Each one needs its own login - they share nothing but the install.' -ForegroundColor DarkGray
    }
}

# ============================================================================
#  Source checkout: build
# ============================================================================

function Invoke-Build {
    # Ant is not on PATH by default and JAVA_HOME is usually unset, so both are resolved
    # here rather than relying on the shell being prepared.
    Step 'Locating the toolchain'

    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Warn 'No Java found on PATH.'
        Warn 'Install Eclipse Temurin JDK 21 (free, official builds of OpenJDK):'
        Warn '  https://adoptium.net/temurin/releases/?version=21'
        Warn 'During setup, tick "Set JAVA_HOME" and "Add to PATH" if the installer offers them,'
        Warn 'then close this window and run Novocaine.ps1 again.'
        Die 'java not found on PATH.'
    }
    # `java -version` prints to STDERR. Redirecting that stderr inside PowerShell (2>&1 or
    # 2>file) makes PS 5.1 wrap it in a TERMINATING NativeCommandError under
    # $ErrorActionPreference='Stop', killing the script on a perfectly good JDK (as a friend
    # hit: openjdk 21.0.11 aborted the script). Letting cmd.exe do the merge means PowerShell
    # only ever receives plain stdout text, never an error record - bulletproof regardless of EAP.
    $javaVerRaw = cmd /c "`"$($java.Source)`" -version 2>&1"
    $javaVersionLine = ($javaVerRaw | Select-Object -First 1)
    if ($javaVersionLine -notmatch '"(1[7-9]|2[01])[."]') {
        Warn "Detected Java version doesn't look like 17-21 (found: $javaVersionLine)"
        Warn 'The client is only tested on Java 17-21; a much older or newer JDK may fail to build or run.'
    }
    $env:JAVA_HOME = (Get-Item $java.Source).Directory.Parent.FullName
    Ok "JAVA_HOME = $env:JAVA_HOME"

    if (-not (Get-Command ant -ErrorAction SilentlyContinue)) {
        $antCandidates = @(Get-ChildItem 'C:\ant' -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'bin' })
        $antBin = $antCandidates | Where-Object { Test-Path (Join-Path $_ 'ant.bat') } | Select-Object -First 1
        if (-not $antBin) {
            Warn 'No Apache Ant found on PATH or under C:\ant.'
            Warn 'Download the "Binary Distributions" zip from the official site:'
            Warn '  https://ant.apache.org/bindownload.cgi'
            Warn 'Extract it so ant.bat ends up at  C:\ant\apache-ant-<version>\bin\ant.bat'
            Warn '(i.e. extract the zip directly into C:\ant), then run Novocaine.ps1 again.'
            Die 'ant not found on PATH or under C:\ant.'
        }
        $env:PATH = "$env:PATH;$antBin"
        Ok "ant = $antBin"
    } else {
        Ok 'ant already on PATH'
    }

    Step 'Building'
    & ant
    if ($LASTEXITCODE -ne 0) { Die 'Build failed. The client was not launched.' }
    Ok 'build succeeded'

    # The alchemy hook reflects into classes the server ships, which the compiler cannot
    # check. Confirm the code is at least present in the build before trusting it.
    Step 'Verifying the alchemy code is in the build'
    $classes = @(
        'build\classes\haven\automated\alchemy\AlchemyBook.class',
        'build\classes\haven\automated\alchemy\AlchemyService.class'
    )
    $missing = $classes | Where-Object { -not (Test-Path (Join-Path $root $_)) }
    if ($missing) {
        Warn "Not found: $($missing -join ', ')"
        Warn 'The client will run, but it will not mirror the Alchemy Book.'
    } else {
        Ok 'alchemy code present in the build'
    }

    Write-Host ''
    Write-Host 'Reminder: the reflective contract cannot be compiler-checked. If the book' -ForegroundColor DarkGray
    Write-Host 'ever reports empty after a game update, run from WSL:' -ForegroundColor DarkGray
    Write-Host '  tools/extract-alchbook.py && tools/check-alchbook-contract.sh' -ForegroundColor DarkGray
}

# ============================================================================
#  Installed client: update
# ============================================================================

# The only host and repository this script will download from. Anything the GitHub API
# hands back that does not live under this prefix is ignored rather than fetched.
$Repo = 'JamesMDTeem/Novocaine'
$AssetPrefix = "https://github.com/$Repo/releases/download/"
$ApiLatest = "https://api.github.com/repos/$Repo/releases/latest"

# -Encoding UTF8 is load-bearing. BuildManifest writes UTF-8 without a BOM, and PS 5.1's
# Get-Content decodes a BOM-less file as the system ANSI codepage - which turns the game's
# res/gfx/hud/meter/hast.res (that is an a-umlaut) into mojibake, so verification reports a
# perfectly good file as missing and throws the whole download away.
function Read-Manifest($path) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $path)) { return $map }
    $json = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($f in $json.files) { $map[$f.path] = $f.sha256 }
    return $map
}

# .NET rather than Get-FileHash: that cmdlet lives in Microsoft.PowerShell.Utility, and if
# this is launched from a PowerShell 7 session the inherited PSModulePath makes
# powershell.exe load PS7's copy of that module instead of its own, at which point
# Get-FileHash does not exist. Novocaine.bat clears PSModulePath to prevent it; not needing
# the cmdlet at all is the belt to that pair of braces. It is also quicker over ~800 files.
function Get-Sha256($path) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $fs = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try { return [BitConverter]::ToString($sha.ComputeHash($fs)).Replace('-', '') }
        finally { $fs.Dispose() }
    } finally { $sha.Dispose() }
}

function Invoke-Update {
    # PS 5.1 still negotiates TLS 1.0 by default on some boxes; github.com refuses it.
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

    $install = $root
    $buildInfo = Join-Path $install 'BUILD-INFO.txt'
    $localTag = $null
    $channel = 'github'
    if (Test-Path $buildInfo) {
        foreach ($line in Get-Content $buildInfo) {
            if ($line -match '^\s*Build:\s*(\S+)')   { $localTag = $Matches[1] }
            if ($line -match '^\s*Channel:\s*(\S+)') { $channel = $Matches[1] }
        }
    }

    if ($channel -eq 'steam') {
        Warn 'This copy came from the Steam Workshop, which Steam keeps up to date itself.'
        Warn 'Nothing to update here.'
        $script:Steam = $true
        return $true
    }
    # Every release carries BUILD-INFO.txt (make-release.ps1 writes it into the zip). A copy
    # without one is not a release - it is a bin\ someone built themselves, and downloading
    # the newest release over the top of it would silently throw their build away. So skip
    # rather than assume out of date; -Force is the way to say "no, really, reinstall".
    if (-not $localTag) {
        if (-not $Force) {
            Warn 'No BUILD-INFO.txt - this is a local build, not an installed release.'
            Warn 'Skipping the update check. Run with -Force to install the latest release over it.'
            return $true
        }
        Warn 'No BUILD-INFO.txt - cannot tell which version is installed; -Force given, reinstalling.'
        $localTag = '(unknown)'
    }
    Ok "installed: $localTag"

    Step 'Checking for a newer release'
    try {
        $rel = Invoke-RestMethod -Uri $ApiLatest -Headers @{
            'Accept'     = 'application/vnd.github+json'
            'User-Agent' = 'Novocaine-Updater'
        } -TimeoutSec 30
    } catch {
        Die "Could not reach GitHub: $($_.Exception.Message)"
    }
    $latestTag = $rel.tag_name
    if (-not $latestTag) { Die 'GitHub returned a release with no tag.' }
    Ok "latest:    $latestTag"

    if (($latestTag -eq $localTag) -and (-not $Force)) {
        Ok 'Already up to date.'
        if ($Check) { exit 0 }
        return $true
    }
    if ($Check) {
        Write-Host "`nUpdate available: $localTag -> $latestTag" -ForegroundColor Cyan
        Write-Host 'Run Novocaine.bat to install it.' -ForegroundColor Cyan
        exit 0
    }

    # --- pick an asset -------------------------------------------------------
    $localVer = $localTag -replace '^nova-', ''
    $latestVer = $latestTag -replace '^nova-', ''
    $deltaName = "Novocaine-$latestVer-from-$localVer.zip"
    $fullName = "Novocaine-$latestVer.zip"

    function Find-Asset($name) {
        $a = $rel.assets | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if (-not $a) { return $null }
        if (-not $a.browser_download_url.StartsWith($AssetPrefix, [StringComparison]::Ordinal)) {
            Warn "Ignoring asset $name - it does not come from $AssetPrefix"
            return $null
        }
        return $a
    }

    $deltaAsset = $null
    if (-not $Full) { $deltaAsset = Find-Asset $deltaName }
    $fullAsset = Find-Asset $fullName
    if (-not $fullAsset) { Die "Release $latestTag has no $fullName asset - nothing to install." }

    $work = Join-Path ([IO.Path]::GetTempPath()) "novocaine-update-$latestVer"
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $work | Out-Null

    function Get-Asset($asset) {
        $dest = Join-Path $work $asset.name
        $mb = [math]::Round($asset.size / 1MB, 1)
        Write-Host "    downloading $($asset.name) ($mb MB)" -ForegroundColor Gray
        $wc = New-Object Net.WebClient
        $wc.Headers.Add('User-Agent', 'Novocaine-Updater')
        try {
            $wc.DownloadFileAsync([Uri]$asset.browser_download_url, $dest)
            while ($wc.IsBusy) {
                Start-Sleep -Milliseconds 400
                if (Test-Path -LiteralPath $dest) {
                    $have = (Get-Item -LiteralPath $dest).Length
                    $pct = 0
                    if ($asset.size -gt 0) { $pct = [math]::Min(100, [math]::Round(100 * $have / $asset.size)) }
                    Write-Host "`r      $pct%   " -NoNewline -ForegroundColor Gray
                }
            }
            Write-Host "`r      100%  " -ForegroundColor Gray
        } finally { $wc.Dispose() }
        if (-not (Test-Path -LiteralPath $dest)) { Die "Download of $($asset.name) produced no file." }
        $got = (Get-Item -LiteralPath $dest).Length
        if (($asset.size -gt 0) -and ($got -ne $asset.size)) {
            Die "Download of $($asset.name) is $got bytes, expected $($asset.size). Try again."
        }
        return $dest
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem

    function Expand-To($zipPath, $dir) {
        if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        [IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $dir)
    }

    function Test-Install($manifestMap) {
        $bad = New-Object Collections.Generic.List[string]
        foreach ($relPath in $manifestMap.Keys) {
            $p = Join-Path $install ($relPath -replace '/', '\')
            if (-not (Test-Path -LiteralPath $p)) { $bad.Add("missing $relPath"); continue }
            if ((Get-Sha256 $p) -ne $manifestMap[$relPath].ToUpperInvariant()) { $bad.Add("corrupt $relPath") }
        }
        return $bad
    }

    # Copying over a jar the running game has open fails loudly rather than half-updating.
    function Copy-Into-Install($from) {
        $fromFull = (Resolve-Path -LiteralPath $from).Path
        foreach ($f in Get-ChildItem -LiteralPath $fromFull -Recurse -File -Force) {
            $relPath = $f.FullName.Substring($fromFull.Length).TrimStart('\')
            $dest = Join-Path $install $relPath
            $parent = Split-Path $dest -Parent
            if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
            try {
                Copy-Item -LiteralPath $f.FullName -Destination $dest -Force
            } catch {
                Die ("Could not write $relPath - is Novocaine still running?`n" +
                     "       Close the game and every extra client window, then run Novocaine.bat again.`n" +
                     "       ($($_.Exception.Message))")
            }
        }
    }

    # Only ever removes paths the OLD release shipped and the new one dropped. Anything the
    # updater never installed - map caches, screenshots, your own files - is left in place.
    function Remove-Dropped($oldMap, $newMap) {
        foreach ($relPath in $oldMap.Keys) {
            if ($newMap.ContainsKey($relPath)) { continue }
            $p = Join-Path $install ($relPath -replace '/', '\')
            if (Test-Path -LiteralPath $p) {
                try { Remove-Item -LiteralPath $p -Force } catch { Warn "could not remove old file $relPath" }
            }
        }
    }

    $oldManifest = Read-Manifest (Join-Path $install 'manifest.json')

    function Apply-Full {
        Step 'Installing the full client'
        $zip = Get-Asset $fullAsset
        $ex = Join-Path $work 'full'
        Expand-To $zip $ex
        $relRoot = Join-Path $ex 'Novocaine'
        if (-not (Test-Path -LiteralPath $relRoot)) { Die 'The release zip has no Novocaine\ folder inside it.' }
        $newMap = Read-Manifest (Join-Path $relRoot 'manifest.json')
        Copy-Into-Install $relRoot
        Remove-Dropped $oldManifest $newMap
        Ok 'full client installed'
        return $newMap
    }

    function Apply-Delta {
        Step "Installing the update ($($deltaAsset.name))"
        $zip = Get-Asset $deltaAsset
        $ex = Join-Path $work 'delta'
        Expand-To $zip $ex
        $payload = Join-Path $ex 'payload'
        if (-not (Test-Path -LiteralPath $payload)) { Die 'Delta package has no payload\ folder.' }
        Copy-Into-Install $payload
        $removed = Join-Path $ex 'removed.txt'
        if (Test-Path -LiteralPath $removed) {
            foreach ($relPath in (Get-Content -LiteralPath $removed -Encoding UTF8)) {
                if (-not $relPath.Trim()) { continue }
                $p = Join-Path $install ($relPath.Trim() -replace '/', '\')
                if (Test-Path -LiteralPath $p) {
                    try { Remove-Item -LiteralPath $p -Force } catch { Warn "could not remove old file $relPath" }
                }
            }
        }
        Ok 'update installed'
        return (Read-Manifest (Join-Path $install 'manifest.json'))
    }

    $usedDelta = $false
    if ($deltaAsset) {
        $newMap = Apply-Delta
        $usedDelta = $true
    } else {
        if (-not $Full) { Warn "No delta from $localVer in this release; fetching the full client." }
        $newMap = Apply-Full
    }

    Step 'Verifying the install'
    if ($newMap.Count -eq 0) {
        Warn 'The release shipped no manifest.json - skipping verification.'
    } else {
        $bad = Test-Install $newMap
        if ($bad.Count -gt 0) {
            if ($usedDelta) {
                Warn "$($bad.Count) file(s) did not verify after the delta; reinstalling the full client."
                $newMap = Apply-Full
                $bad = Test-Install $newMap
            }
            if ($bad.Count -gt 0) {
                $bad | Select-Object -First 10 | ForEach-Object { Warn $_ }
                Die "The install did not verify. Download $fullName by hand from https://github.com/$Repo/releases"
            }
        }
        Ok "$($newMap.Count) files verified"
    }

    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue

    $now = $localTag
    if (Test-Path $buildInfo) {
        foreach ($line in Get-Content $buildInfo) { if ($line -match '^\s*Build:\s*(\S+)') { $now = $Matches[1] } }
    }
    Ok "now on $now"
    return $true
}

# ============================================================================
#  Do it
# ============================================================================

if ($isSource) {
    Set-Location $root
    if ($Check) { Die '-Check only applies to an installed client; a checkout is updated with git.' }
    if (-not $NoBuild) { Invoke-Build }

    $bin = Join-Path $root 'bin'
    if ($NoLaunch) { Step 'Done (-NoLaunch); not starting the game'; exit 0 }
    if (-not (Test-Path (Join-Path $bin 'hafen.jar'))) {
        Die "No build in $bin -- run without -NoBuild first."
    }
    Start-Client $bin $Count
} else {
    if (-not $NoUpdate) { [void](Invoke-Update) }
    # -Check is a question, never a launch - including on the paths where Invoke-Update
    # bails out early (a Workshop copy, or a local build with no BUILD-INFO.txt).
    if ($Check) { exit 0 }
    if ($NoLaunch) { Step 'Done (-NoLaunch); not starting the game'; exit 0 }
    Start-Client $root $Count
}
