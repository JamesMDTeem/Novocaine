<#
.SYNOPSIS
Builds the trimmed Java runtime that ships next to the client.

.DESCRIPTION
Every launcher prefers a bundled runtime if one is present:

    if exist "%~dp0jre\bin\java.exe" set "JAVA=%~dp0jre\bin\java.exe"

so dropping a runtime into bin\jre is the whole of "bundle a JVM". This produces one
with jlink, containing only the modules the client actually resolves.

The module list is not a guess. It came from jdeps against the shipped jars:

    jdeps --print-module-deps --ignore-missing-deps hafen.jar
      -> java.base, java.compiler, java.desktop, java.management, java.prefs, java.sql

plus jdk.unsupported, which lwjgl-fat.jar needs for sun.misc.Unsafe. That one is the
trap: leave it out and the build succeeds, then the client dies at runtime the moment
anything touches LWJGL. Re-derive the list with -Verify if the jars change.

java.compiler is load-bearing, not incidental - the client compiles resource code at
runtime - so do not trim it on the assumption that a game has no use for javac.

Measured with Temurin 25.0.4.1: 49 MB output against a 316 MB full JDK, and the client
runs on it with a window.

jmods are NOT required. JDK 24 gained the linkable runtime (JEP 493), so jlink works
against a stock Temurin install even though its default MSI omits the jmods component.

.PARAMETER Jdk
JDK to link from. Defaults to the newest Temurin found, else JAVA_HOME.

.PARAMETER Out
Where to write the runtime. Defaults to bin\jre, which is where the launchers look.

.PARAMETER Verify
Re-derive the module list with jdeps and report any drift from the list below.

.EXAMPLE
    .\tools\make-jre.ps1
    .\tools\make-jre.ps1 -Verify
#>
[CmdletBinding()]
param(
    [string]$Jdk,
    [string]$Out,
    [switch]$Verify
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

# Modules the client resolves. See the header for how this was derived.
$modules = @(
    'java.base'
    'java.compiler'      # the client compiles resource code at runtime
    'java.desktop'       # AWT/Swing, and the JOGL canvas underneath it
    'java.management'
    'java.prefs'         # every Utils.getpref* call lands here
    'java.sql'           # sqlite-jdbc
    'jdk.unsupported'    # sun.misc.Unsafe, required by lwjgl-fat.jar
)

function Find-Jdk {
    if ($Jdk) { return $Jdk }
    $roots = @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java')
    $best = $null; $bestVer = -1
    foreach ($r in $roots) {
        if (-not (Test-Path $r)) { continue }
        foreach ($d in Get-ChildItem $r -Directory -ErrorAction SilentlyContinue) {
            if (-not (Test-Path (Join-Path $d.FullName 'bin\jlink.exe'))) { continue }
            if ($d.Name -match '(\d+)') {
                $v = [int]$Matches[1]
                if ($v -gt $bestVer) { $bestVer = $v; $best = $d.FullName }
            }
        }
    }
    if ($best) { return $best }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\jlink.exe'))) { return $env:JAVA_HOME }
    throw 'No JDK with jlink found. Pass -Jdk, or install one (winget install EclipseAdoptium.Temurin.25.JDK).'
}

$jdkPath = Find-Jdk
$jlink = Join-Path $jdkPath 'bin\jlink.exe'
# --version (two dashes) writes to stdout; the older -version writes to stderr, and
# redirecting a native exe with 2>&1 on Windows PowerShell 5.1 yields ErrorRecords and
# sets $? false even on success. Avoid the redirect entirely.
$ver = & (Join-Path $jdkPath 'bin\java.exe') --version | Select-Object -First 1

Write-Host "==> Linking from"
Write-Host "    $jdkPath"
Write-Host "    $ver"

if ($Verify) {
    $jdeps = Join-Path $jdkPath 'bin\jdeps.exe'
    $binDir = Join-Path $repo 'bin'
    $jars = Get-ChildItem $binDir -Filter '*.jar' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    if (-not $jars) { throw "No jars in $binDir - build first." }
    Write-Host "==> Re-deriving module list with jdeps"
    # --multi-release is required: sqlite-jdbc is a multi-release jar and jdeps refuses
    # to read one without it, which otherwise surfaces as an error string that looks like
    # a module name.
    $derived = & $jdeps --print-module-deps --ignore-missing-deps --multi-release 25 @jars 2>$null
    if ($derived -and ($derived -notmatch '^Error:')) {
        $set = ($derived -split ',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
        $missing = $set | Where-Object { $_ -notin $modules }
        if ($missing) {
            Write-Warning "jdeps reports modules not in the list above: $($missing -join ', ')"
            Write-Warning 'Add them to $modules in this script, or the client will fail at runtime.'
        } else {
            Write-Host "    no drift - the list above still covers what jdeps resolves"
        }
    } elseif ($derived) {
        Write-Warning "jdeps could not analyse the jars: $derived"
    }
}

$outPath = if ($Out) { $Out } else { Join-Path $repo 'bin\jre' }
if (Test-Path $outPath) {
    # Recursive force-delete: refuse anything that is not recognisably a jlink runtime, so a
    # mistyped -Out cannot take a real directory with it.
    $looksLikeRuntime = (Test-Path (Join-Path $outPath 'bin\java.exe')) -and (Test-Path (Join-Path $outPath 'release'))
    if (-not $looksLikeRuntime) {
        throw "$outPath exists but does not look like a jlink runtime (no bin\java.exe + release). Refusing to delete it. Remove it yourself, or pass a different -Out."
    }
    Write-Host "==> Replacing existing runtime at $outPath"
    Remove-Item -LiteralPath $outPath -Recurse -Force
}

Write-Host "==> jlink"
& $jlink `
    --add-modules ($modules -join ',') `
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 `
    --output $outPath
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE" }

$size = (Get-ChildItem $outPath -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ("    {0} MB at {1}" -f [math]::Round($size / 1MB), $outPath)

Write-Host "==> Smoke test"
$probe = & (Join-Path $outPath 'bin\java.exe') --version | Select-Object -First 1
Write-Host "    $probe"

Write-Host ""
Write-Host "Done. The launchers pick this up automatically - Play.bat prefers"
Write-Host "%~dp0jre\bin\java.exe over system Java with no further changes."
Write-Host "Note that bin\ is gitignored, so this runtime is a build artifact, not"
Write-Host "something to commit; regenerate it as part of packaging."
