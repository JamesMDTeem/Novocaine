<#
.SYNOPSIS
    Assemble a Novocaine Steam Workshop item from the built client, and optionally upload it
    to the Steam Workshop with friends-only visibility.

.DESCRIPTION
    A Steam Workshop item is just a directory of files plus a workshop-client.properties
    describing how to launch and how it's presented. Novocaine's item is the built bin\
    client (launched via its hafen.hl chain) with the Novocaine metadata from
    steam\workshop-client.properties overlaid on top.

    This script:
        1. Builds a clean client (unless -SkipBuild), same as the release script.
        2. Stages bin\ into dist\steam-item\ and overlays everything from steam\ (the
           Novocaine workshop-client.properties, and any preview image you dropped there)
           - so the Hurricane workshop-client.properties that ships in bin\ is REPLACED and
           Nightdawg's item id is never used.
        3. With -Upload, runs Loftar's uploader (haven.SteamWorkshop upload) against the
           staged item. Without -Upload, it just stages and prints the command so you can
           inspect dist\steam-item first.

    The upload itself is yours to run: it needs Steam running and logged in, beta access to
    the game, and the Workshop Legal Agreement accepted (see steam\README.md). This script
    never uploads unless you pass -Upload.

.PARAMETER Upload
    Actually run the upload. Requires Steam running + logged in. Omit to stage only.

.PARAMETER Message
    Optional "commit message" recorded with the upload (Steam supports this).

.PARAMETER SkipBuild
    Zip/stage whatever is already in bin\ instead of rebuilding first.
#>

[CmdletBinding()]
param(
    [switch]$Upload,
    [string]$Message,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host "`n!! $m" -ForegroundColor Red; exit 1 }

$steamSrc = Join-Path $repoRoot 'steam'
$propFile = Join-Path $steamSrc 'workshop-client.properties'
if (-not (Test-Path $propFile)) { Die "Missing $propFile - the Novocaine workshop metadata." }

# --- build -----------------------------------------------------------------
if ($SkipBuild) {
    Step 'Skipping build (-SkipBuild)'
    if (-not (Test-Path 'bin\hafen.jar')) { Die 'bin\hafen.jar missing - cannot skip the build.' }
} else {
    Step 'Building a clean client'
    & "$repoRoot\update-and-play.ps1" -SkipUpdate -NoLaunch
    if ($LASTEXITCODE -ne 0) { Die 'Build failed; item not assembled.' }
}
# The uploader must run from bin\, not build\. haven.SteamWorkshop pulls in haven.Client,
# whose static init loads HUD resources - and only bin\hafen.jar's manifest Class-Path
# chains in builtin-res.jar/hafen-res.jar. Running it against build\hafen.jar dies with
# NoSuchResourceException on gfx/hud/wnd/lg/rm before it ever talks to Steam.
if (-not (Test-Path 'bin\hafen.jar')) { Die 'bin\hafen.jar not found - the uploader runs from it.' }

# --- stage the item --------------------------------------------------------
Step 'Assembling the Workshop item'
$item = Join-Path $repoRoot 'dist\steam-item'
if (Test-Path $item) { Remove-Item $item -Recurse -Force }
New-Item -ItemType Directory -Force -Path $item | Out-Null
Copy-Item -Path 'bin\*' -Destination $item -Recurse -Force
# Local per-character/session state shouldn't ship.
foreach ($junk in @('logs', 'lp', 'alchemy-book-dump.json', '.pre-update-backup')) {
    $p = Join-Path $item $junk
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}
# Overlay the Novocaine steam metadata (and any preview image) OVER bin\'s Hurricane copy.
Copy-Item -Path (Join-Path $steamSrc '*') -Destination $item -Recurse -Force -Exclude 'README.md'

# Sanity: the staged properties must be ours, not Hurricane's.
$stagedProps = Get-Content (Join-Path $item 'workshop-client.properties') -Raw
if ($stagedProps -notmatch 'name=Novocaine') { Die 'Staged workshop-client.properties is not the Novocaine one - aborting to avoid touching the wrong Workshop item.' }
$visibility = if ($stagedProps -match 'visibility=(\w+)') { $Matches[1] } else { 'unknown' }
$hasId = $stagedProps -match 'workshop-id=\d+'
Ok "item staged at $item (visibility=$visibility, $(if ($hasId) { 'updating existing item' } else { 'will CREATE a new item' }))"

# --- upload ----------------------------------------------------------------
if (-not $Upload) {
    Step 'Staged only (no -Upload)'
    Write-Host ''
    Write-Host 'Inspect the item, then upload it yourself with:' -ForegroundColor DarkGray
    Write-Host "  `$env:SteamAppID = '3051280'" -ForegroundColor DarkGray
    Write-Host "  cd bin; java -cp hafen.jar haven.SteamWorkshop upload `"$item`"" -ForegroundColor DarkGray
    Write-Host 'or re-run this script with -Upload (Steam must be running and logged in).' -ForegroundColor DarkGray
    exit 0
}

Step 'Uploading to the Steam Workshop'
Warn 'This needs Steam running + logged in, beta access to the game, and the Workshop Legal'
Warn 'Agreement accepted. See steam\README.md if the upload is refused.'
$env:SteamAppID = '3051280'
$javaArgs = @('-cp', 'hafen.jar', 'haven.SteamWorkshop', 'upload', $item)
if ($Message) { $javaArgs += $Message }
# $item is absolute, so running from bin\ does not change what gets uploaded.
Push-Location (Join-Path $repoRoot 'bin')
try { & java @javaArgs } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { Die 'Upload failed (see the messages above).' }

Ok 'Upload finished.'
if (-not $hasId) {
    Warn 'This was a NEW item - copy the "workshop-id=..." line the tool printed above into'
    Warn 'steam\workshop-client.properties (then commit it) so future uploads update this same item.'
}
