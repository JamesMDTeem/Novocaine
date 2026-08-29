<#
.SYNOPSIS
    Build once, then launch several Novocaine clients at once - one per crew member.

.DESCRIPTION
    One Haven client is one character, so a crew of eight is eight JVMs. This builds the
    tree once through build-and-play.ps1 -NoLaunch and then starts N clients in parallel.

    Deliberately NOT "run build-and-play.ps1 N times". That would rebuild the same tree
    eight times, and - the part that actually matters - build-and-play.ps1 launches the
    game with `cmd /c Play.bat`, which BLOCKS until that client is closed. Run in a loop
    it would give you one client at a time, eight times in a row, which is the opposite
    of a crew.

    The JVM arguments are read out of bin\Play.bat rather than written out again here,
    so heap sizes and --add-exports flags stay in one place.

.PARAMETER Count
    How many clients to start. Default 8.

.PARAMETER NoBuild
    Skip the build and just launch, for starting more clients against a build you already
    have.

.PARAMETER StaggerSeconds
    Seconds between launches. Eight JVMs creating GL contexts in the same instant is how
    you get one of them failing on a driver timeout; a few seconds apart costs nothing.

.PARAMETER DryRun
    Print the command line each client would be started with, and start nothing. The
    arguments are parsed out of bin\Play.bat, so this is how you check that parse without
    putting eight game windows on screen.

.EXAMPLE
    .\play-crew.ps1
    .\play-crew.ps1 -Count 4
    .\play-crew.ps1 -Count 2 -NoBuild
    .\play-crew.ps1 -NoBuild -DryRun
#>

[CmdletBinding()]
param(
    [int]$Count = 8,
    [switch]$NoBuild,
    [int]$StaggerSeconds = 3,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot
Set-Location $repo

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "    $msg" -ForegroundColor Yellow }
function Die($msg)  { Write-Host "`n!! $msg" -ForegroundColor Red; exit 1 }

if ($Count -lt 1) { Die "Count must be at least 1 (got $Count)." }

# --- build once ------------------------------------------------------------
if (-not $NoBuild) {
    Step "Building once for all $Count clients"
    & (Join-Path $repo 'build-and-play.ps1') -NoLaunch
    if ($LASTEXITCODE -ne 0) { Die 'Build failed. No clients were launched.' }
}

# --- work out how to start one ---------------------------------------------
$bin = Join-Path $repo 'bin'
if (-not (Test-Path (Join-Path $bin 'hafen.jar'))) {
    Die "No build in $bin -- run without -NoBuild first."
}

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { Die 'java not found on PATH.' }

# Play.bat is: `"%JAVA%" <flags> -jar hafen.jar` (console) or `start "Novocaine" "%JAVA%" <flags> -jar hafen.jar` (no-console).
# Take everything after the executable and hand it straight to Start-Process, so the flags have exactly one home.
$playBat = Join-Path $bin 'Play.bat'
if (-not (Test-Path $playBat)) { Die "No Play.bat in $bin." }
$line = Get-Content $playBat | Where-Object { $_ -match 'hafen\.jar' -and $_ -notmatch '^\s*REM' -and $_ -notmatch '^\s*@' } | Select-Object -First 1
if (-not $line) { Die "Couldn't find the java command line in $playBat." }
$jvmArgs = $line.Trim() -replace '^start\s+"[^"]*"\s+', ''
$jvmArgs = $jvmArgs -replace '^\s*"?%JAVA%?"?\s+', ''
$jvmArgs = $jvmArgs -replace '^\s*java(\.exe)?"?\s+', ''
$jvmArgs = $jvmArgs.Trim()
if (-not $jvmArgs) { Die "Parsed an empty argument list out of $playBat." }

# A heads-up rather than a limit: -Xms is COMMITTED per process, so the floor is real
# even when nothing is using it. The -Xmx ceiling only matters if they all fill up.
if ($jvmArgs -match '-Xms(\d+)m') {
    $floorGb = [math]::Round(([int]$Matches[1] * $Count) / 1024.0, 1)
    Warn "$Count clients reserve about $floorGb GB of heap between them before anything loads."
}

# --- launch ----------------------------------------------------------------
# The working directory must be bin\: botclaims\, botplaces.json, botmap.json and the
# logs are all resolved relative to it, and every client in a crew has to land on the
# SAME ones or none of the cross-process coordination works.
if ($DryRun) {
    Step "Dry run -- nothing is being started"
    Ok "working directory: $bin"
    Ok "would run $Count x: $($java.Source) $jvmArgs"
    exit 0
}

Step "Launching $Count client(s) from $bin"
$started = @()
for ($i = 1; $i -le $Count; $i++) {
    $p = Start-Process -FilePath $java.Source -ArgumentList $jvmArgs -WorkingDirectory $bin -PassThru
    $started += $p
    Ok "client $i of $Count -- pid $($p.Id)"
    if (($i -lt $Count) -and ($StaggerSeconds -gt 0)) { Start-Sleep -Seconds $StaggerSeconds }
}

Write-Host ''
Ok "$($started.Count) client(s) running."
Write-Host "    Stop them all with:  Stop-Process -Id $($started.Id -join ',')" -ForegroundColor DarkGray
Write-Host '    Each one needs its own login - they share nothing but the install.' -ForegroundColor DarkGray
