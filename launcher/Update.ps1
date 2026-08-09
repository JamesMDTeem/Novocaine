<#
.SYNOPSIS
    Update this Novocaine install to the newest GitHub release, then start the game.

.DESCRIPTION
    Ships inside the release zip and lives next to Play.bat. Run it (via Update.bat)
    instead of Play.bat and you stop re-downloading the client by hand.

    What it does:
      1. Reads the installed version from BUILD-INFO.txt.
      2. Asks GitHub for the latest Novocaine release.
      3. If it is newer, downloads it - preferring the small "from-<your version>" delta
         over the ~170MB full zip - applies it, and verifies every file against the
         release's manifest.json. A failed verification after a delta falls back to the
         full zip automatically.
      4. Launches Play.bat.

    Your settings are never touched: the client keeps those in the Windows registry and
    in %APPDATA%\Haven and Hearth, not in the install folder. Files the install has that
    the release does not know about (map caches, screenshots, anything you dropped in)
    are left alone - the updater only ever writes paths the release names, and only ever
    deletes paths the PREVIOUS release named and the new one dropped.

.PARAMETER NoLaunch
    Update only; do not start the game afterwards.

.PARAMETER Check
    Report whether an update exists and exit without downloading anything.

.PARAMETER Force
    Re-apply the latest release even if the installed version already matches. Use this
    to repair an install whose files have been damaged.

.PARAMETER Full
    Skip the delta and download the complete client.
#>

[CmdletBinding()]
param(
    [switch]$NoLaunch,
    [switch]$Check,
    [switch]$Force,
    [switch]$Full
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # PS 5.1's progress bar makes web calls crawl

# The only host and repository this script will download from. Anything the GitHub API
# hands back that does not live under this prefix is ignored rather than fetched.
$Repo = 'JamesMDTeem/Novocaine'
$AssetPrefix = "https://github.com/$Repo/releases/download/"
$ApiLatest = "https://api.github.com/repos/$Repo/releases/latest"

# The install is wherever this script sits. Update.bat runs a COPY of it out of %TEMP%
# precisely so this folder can be rewritten underneath us, so trust the parameter the bat
# passes rather than $PSScriptRoot when it is there.
$install = if ($env:NOVOCAINE_HOME) { $env:NOVOCAINE_HOME } else { $PSScriptRoot }

function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    $m" -ForegroundColor Yellow }
function Die($m)  {
    Write-Host "`n!! $m" -ForegroundColor Red
    Write-Host "`nPress Enter to close." -ForegroundColor Yellow
    [void](Read-Host)
    exit 1
}

# PS 5.1 still negotiates TLS 1.0 by default on some boxes; github.com refuses it.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# --- where are we ------------------------------------------------------------
if (-not (Test-Path (Join-Path $install 'hafen.jar'))) {
    Die "This does not look like a Novocaine install (no hafen.jar in $install)."
}

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
    Warn 'Nothing to do here - just launch the game from Steam.'
    if (-not $NoLaunch) { Start-Process -FilePath (Join-Path $install 'Play_WithSteam.bat') -WorkingDirectory $install }
    exit 0
}
if (-not $localTag) {
    Warn 'No BUILD-INFO.txt - cannot tell which version is installed, so treating it as out of date.'
    $localTag = '(unknown)'
}
Ok "installed: $localTag"

# --- what's out there --------------------------------------------------------
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
    if (-not $NoLaunch) { Start-Process -FilePath (Join-Path $install 'Play.bat') -WorkingDirectory $install }
    exit 0
}
if ($Check) {
    Write-Host "`nUpdate available: $localTag -> $latestTag" -ForegroundColor Cyan
    Write-Host 'Run Update.bat to install it.' -ForegroundColor Cyan
    exit 0
}

# --- pick an asset -----------------------------------------------------------
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

$deltaAsset = if ($Full) { $null } else { Find-Asset $deltaName }
$fullAsset = Find-Asset $fullName
if (-not $fullAsset) { Die "Release $latestTag has no $fullName asset - nothing to install." }

$manifestAsset = Find-Asset 'manifest.json'

# --- download helper ---------------------------------------------------------
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
                $pct = if ($asset.size -gt 0) { [math]::Min(100, [math]::Round(100 * $have / $asset.size)) } else { 0 }
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

# --- manifest helpers --------------------------------------------------------
function Read-Manifest($path) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $path)) { return $map }
    $json = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    foreach ($f in $json.files) { $map[$f.path] = $f.sha256 }
    return $map
}

# .NET rather than Get-FileHash: that cmdlet lives in Microsoft.PowerShell.Utility, and if
# this is launched from a PowerShell 7 session the inherited PSModulePath makes
# powershell.exe load PS7's copy of that module instead of its own, at which point
# Get-FileHash does not exist. Update.bat clears PSModulePath to prevent it; not needing the
# cmdlet at all is the belt to that pair of braces. It is also quicker over ~800 files.
function Get-Sha256($path) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $fs = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try { return [BitConverter]::ToString($sha.ComputeHash($fs)).Replace('-', '') }
        finally { $fs.Dispose() }
    } finally { $sha.Dispose() }
}

function Test-Install($manifestMap) {
    $bad = New-Object Collections.Generic.List[string]
    foreach ($rel in $manifestMap.Keys) {
        $p = Join-Path $install ($rel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $p)) { $bad.Add("missing $rel"); continue }
        if ((Get-Sha256 $p) -ne $manifestMap[$rel].ToUpperInvariant()) { $bad.Add("corrupt $rel") }
    }
    return $bad
}

# --- apply -------------------------------------------------------------------
# Copying over a jar the running game has open fails loudly rather than half-updating.
function Copy-Into-Install($from) {
    $fromFull = (Resolve-Path -LiteralPath $from).Path
    foreach ($f in Get-ChildItem -LiteralPath $fromFull -Recurse -File -Force) {
        $rel = $f.FullName.Substring($fromFull.Length).TrimStart('\')
        $dest = Join-Path $install $rel
        $parent = Split-Path $dest -Parent
        if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
        try {
            Copy-Item -LiteralPath $f.FullName -Destination $dest -Force
        } catch {
            Die ("Could not write $rel - is Novocaine still running?`n" +
                 "       Close the game and every extra client window, then run Update.bat again.`n" +
                 "       ($($_.Exception.Message))")
        }
    }
}

# Only ever removes paths the OLD release shipped and the new one dropped. Anything the
# updater never installed - map caches, screenshots, your own files - is left in place.
function Remove-Dropped($oldMap, $newMap) {
    foreach ($rel in $oldMap.Keys) {
        if ($newMap.ContainsKey($rel)) { continue }
        $p = Join-Path $install ($rel -replace '/', '\')
        if (Test-Path -LiteralPath $p) {
            try { Remove-Item -LiteralPath $p -Force } catch { Warn "could not remove old file $rel" }
        }
    }
}

$oldManifest = Read-Manifest (Join-Path $install 'manifest.json')

function Apply-Full {
    Step 'Installing the full client'
    $zip = Get-Asset $fullAsset
    $ex = Join-Path $work 'full'
    Expand-To $zip $ex
    $root = Join-Path $ex 'Novocaine'
    if (-not (Test-Path -LiteralPath $root)) { Die 'The release zip has no Novocaine\ folder inside it.' }
    $newMap = Read-Manifest (Join-Path $root 'manifest.json')
    Copy-Into-Install $root
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
        foreach ($rel in Get-Content -LiteralPath $removed) {
            if (-not $rel.Trim()) { continue }
            $p = Join-Path $install ($rel.Trim() -replace '/', '\')
            if (Test-Path -LiteralPath $p) {
                try { Remove-Item -LiteralPath $p -Force } catch { Warn "could not remove old file $rel" }
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

# --- verify ------------------------------------------------------------------
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

if (-not $NoLaunch) {
    Step 'Starting Novocaine'
    Start-Process -FilePath (Join-Path $install 'Play.bat') -WorkingDirectory $install
}
