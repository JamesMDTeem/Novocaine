<#
.SYNOPSIS
    Smoke-test a staged Workshop item by launching it the way Steam actually does, and
    report whether the client survives startup.

.DESCRIPTION
    A client that runs fine from bin\Novocaine.bat can still be dead on arrival under Steam,
    because the two launch paths differ in every way that matters:

      * Novocaine.bat runs `javaw -jar hafen.jar` (flags read out of Play.bat) with the
        item directory as the working directory, so bare relative paths happen to resolve.
      * The Steam launcher copies each jar into
        %LOCALAPPDATA%\Haven Launcher\cache\file\<percent-encoded item path>\ and runs
        the child JVM from the GAME INSTALL directory with that cache on the classpath.
        The client's own jar therefore lives in a directory containing no resources, and
        the working directory is not the item either.

    That difference is what shipped a Workshop item whose client died in static init on
    gfx/hud/fonttexUnfocused. This script exists so that cannot happen again unnoticed.

    It drives the real launcher.jar, captures the javaw child's command line, then re-runs
    that exact command with java.exe (javaw has no console) and inspects stderr.

    KNOWN LIMITATION: the client locates its resources through gameDir, which walks up
    from the working directory into steamapps\workshop\content\<app>\<item>\ - i.e. the
    DOWNLOADED copy of the item, not the staged directory under test. So this catches
    launch/wiring regressions in the code, but it cannot catch a change to res\ that has
    not been uploaded yet. Treat a pass as "the client starts", not "the payload is
    byte-correct".

.PARAMETER Item
    Staged (or downloaded) Workshop item directory to launch. Defaults to dist\steam-item.

.PARAMETER GameDir
    The Haven & Hearth Steam install. Defaults to the standard location.

.PARAMETER TimeoutSec
    How long to let the client run before calling it a pass. Default 60.

.OUTPUTS
    Exit 0 = client started (pass), 1 = client threw during startup (fail),
    2 = could not run the test (missing launcher, game install, or item).
#>

[CmdletBinding()]
param(
    [string]$Item,
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\Haven',
    [int]$TimeoutSec = 60
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $Item) { $Item = Join-Path $repoRoot 'dist\steam-item' }

function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    $m" -ForegroundColor Yellow }
function Bad($m)  { Write-Host "    $m" -ForegroundColor Red }

if (-not (Test-Path $Item))    { Bad "No such item directory: $Item"; exit 2 }
if (-not (Test-Path $GameDir)) { Bad "Haven install not found: $GameDir"; exit 2 }
$javaExe = Join-Path $GameDir 'jre\bin\java.exe'
$launcherJar = Join-Path $GameDir 'launcher.jar'
$launcherHl = Join-Path $Item 'launcher.hl'
foreach ($p in @($javaExe, $launcherJar, $launcherHl)) {
    if (-not (Test-Path $p)) { Bad "Missing: $p"; exit 2 }
}

function Stop-Clients {
    Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -match 'haven\.Client' } |
        ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch {} }
}

Stop-Clients
$env:SteamAppID = '3051280'

Step 'Driving the real launcher to capture the client command line'
$before = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
            Select-Object -ExpandProperty ProcessId)
Start-Process -FilePath $javaExe -ArgumentList @('-jar', 'launcher.jar', "`"$launcherHl`"") `
              -WorkingDirectory $GameDir -NoNewWindow | Out-Null

$child = $null
for ($i = 0; $i -lt ($TimeoutSec * 2) -and -not $child; $i++) {
    $child = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
             Where-Object { $before -notcontains $_.ProcessId -and $_.CommandLine -match 'haven\.Client' } |
             Select-Object -First 1
    if (-not $child) { Start-Sleep -Milliseconds 500 }
}
if (-not $child) { Bad 'The launcher never spawned a client JVM.'; Stop-Clients; exit 2 }
$cmdline = $child.CommandLine
try { Stop-Process -Id $child.ProcessId -Force -ErrorAction Stop } catch {}
Stop-Clients
Ok 'captured'

# Split off the leading quoted exe and keep the remainder verbatim - it contains
# %-encoded cache paths and quoted arguments that must not be re-parsed.
if ($cmdline -notmatch '^"([^"]+)"\s*(.*)$') { Bad 'Could not parse the child command line.'; exit 2 }
$childArgs = $Matches[2]

Step 'Re-running the client with a console attached'
$outF = Join-Path $env:TEMP 'novo-smoke-out.txt'
$errF = Join-Path $env:TEMP 'novo-smoke-err.txt'
Remove-Item $outF, $errF -Force -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath $javaExe -ArgumentList $childArgs -WorkingDirectory $GameDir `
                      -NoNewWindow -PassThru -RedirectStandardOutput $outF -RedirectStandardError $errF
$survived = -not $proc.WaitForExit($TimeoutSec * 1000)
if ($survived) { try { Stop-Process -Id $proc.Id -Force } catch {} }
Stop-Clients

$out = ((Get-Content $outF -ErrorAction SilentlyContinue) +
        (Get-Content $errF -ErrorAction SilentlyContinue)) -join "`n"

if ($out -match 'ExceptionInInitializerError|NoSuchResourceException|Exception in thread "main"') {
    Write-Host ''
    Write-Host (($out -split "`n" | Select-Object -First 30) -join "`n")
    if ($out -match 'filesystem res source \(([^)]*)\)') { Warn "resource source was: $($Matches[1])" }
    Bad 'SMOKE TEST FAILED - the client crashes on the Steam launch path.'
    exit 1
}

if ($survived) { Ok 'SMOKE TEST PASSED - client started and stayed up.' }
else           { Ok "SMOKE TEST PASSED - client exited cleanly (code $($proc.ExitCode))." }
exit 0
