<#
.SYNOPSIS
    Build the Novocaine client from what is checked out, verify the alchemy code is
    present in the build, and launch.

.DESCRIPTION
    Build and run the Novocaine fork (a custom Haven & Hearth client built on
    Nightdawg's Hurricane). This script never touches git: it builds whatever is
    checked out on the current branch, confirms the alchemy integration made it into
    the build, and starts the game.

    master is the trunk. Upstream changes are pulled in deliberately, one at a time,
    through tools/merge-upstream.ps1 (List/Diff/Import/Pick) - never by this script.
    The old auto-update flow (check out an upstream release, re-apply the fork as a
    vendor-baseline..alchemy patch, re-tag) is retired; those tags remain only as a
    static historical reference.

.PARAMETER NoLaunch
    Build, but do not start the game.

.EXAMPLE
    .\build-and-play.ps1
    .\build-and-play.ps1 -NoLaunch
#>

[CmdletBinding()]
param(
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot
Set-Location $repo

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "    $msg" -ForegroundColor Yellow }
function Die($msg)  { Write-Host "`n!! $msg" -ForegroundColor Red; exit 1 }

# --- toolchain -------------------------------------------------------------
# Ant is not on PATH by default and JAVA_HOME is usually unset, so both are
# resolved here rather than relying on the shell being prepared.
Step 'Locating the toolchain'

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Warn 'No Java found on PATH.'
    Warn 'Install Eclipse Temurin JDK 21 (free, official builds of OpenJDK):'
    Warn '  https://adoptium.net/temurin/releases/?version=21'
    Warn 'During setup, tick "Set JAVA_HOME" and "Add to PATH" if the installer offers them,'
    Warn 'then close this window and run build-and-play.ps1 again.'
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
        Warn '(i.e. extract the zip directly into C:\ant), then run build-and-play.ps1 again.'
        Die 'ant not found on PATH or under C:\ant.'
    }
    $env:PATH = "$env:PATH;$antBin"
    Ok "ant = $antBin"
} else {
    Ok 'ant already on PATH'
}

# --- build -----------------------------------------------------------------
Step 'Building'
& ant
if ($LASTEXITCODE -ne 0) { Die 'Build failed. The client was not launched.' }
Ok 'build succeeded'

# --- sanity ----------------------------------------------------------------
# The alchemy hook reflects into classes the server ships, which the compiler cannot
# check. Confirm the code is at least present in the build before trusting it.
Step 'Verifying the alchemy code is in the build'
$classes = @(
    'build\classes\haven\automated\alchemy\AlchemyBook.class',
    'build\classes\haven\automated\alchemy\AlchemyService.class'
)
$missing = $classes | Where-Object { -not (Test-Path $_) }
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

# --- launch ----------------------------------------------------------------
if ($NoLaunch) {
    Step 'Done (-NoLaunch); not starting the game'
    exit 0
}

Step 'Launching'
$bin = Join-Path $repo 'bin'
if (-not (Test-Path (Join-Path $bin 'hafen.jar'))) { Die "No build in $bin -- ant did not stage the client." }
# The game's working directory must be bin\: alchemy-book-dump.json and the
# [Alchemy] console output land relative to it.
Push-Location $bin
try {
    & cmd.exe /c 'Play.bat'
} finally {
    Pop-Location
}
