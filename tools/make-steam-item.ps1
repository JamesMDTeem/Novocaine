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

.PARAMETER SkipSmoke
    Skip the pre-upload smoke test. The test launches the staged item the way Steam does
    and refuses the upload if the client dies on startup - a client can run perfectly from
    bin\Novocaine.bat and still be dead under the Steam launcher, which is how a broken item got
    published once. Only use this if the test itself is broken or the game is not installed.
#>

[CmdletBinding()]
param(
    [switch]$Upload,
    [string]$Message,
    [switch]$SkipBuild,
    [switch]$SkipSmoke
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
    & "$repoRoot\build-and-play.ps1" -NoLaunch
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
# Only shippable files, never the running client's world state - see tools\stage-client.ps1.
# The old copy-everything-then-delete-four-names approach published one player's botmap,
# drawn areas and work claims to the Workshop.
& (Join-Path $PSScriptRoot 'stage-client.ps1') -Destination $item -RepoRoot $repoRoot -NoManifest | Out-Null
# Steam keeps Workshop items updated itself, so the item ships no updater. The channel
# marker is belt-and-braces for a copy that ends up somewhere odd: Novocaine.ps1 reads it and
# declines rather than pulling a GitHub build over a Steam install.
$steamMeta = "Build: steam-workshop`nChannel: steam`nCommit: $(git rev-parse HEAD)`n"
[IO.File]::WriteAllText((Join-Path $item 'BUILD-INFO.txt'), $steamMeta, [Text.UTF8Encoding]::new($false))
# Overlay the Novocaine steam metadata (and any preview image) OVER bin\'s Hurricane copy.
Copy-Item -Path (Join-Path $steamSrc '*') -Destination $item -Recurse -Force -Exclude 'README.md'
# The overlay replaced files the staged manifest had already hashed; redo it so the item's
# manifest.json describes the item that actually ships.
& java -cp (Join-Path $repoRoot 'bin\hafen.jar') haven.BuildManifest $item (Join-Path $item 'manifest.json')
if ($LASTEXITCODE -ne 0) { Die 'Failed to regenerate manifest.json for the Workshop item.' }

# Sanity: the staged properties must be ours, not Hurricane's.
$stagedProps = Get-Content (Join-Path $item 'workshop-client.properties') -Raw
if ($stagedProps -notmatch 'name=Novocaine') { Die 'Staged workshop-client.properties is not the Novocaine one - aborting to avoid touching the wrong Workshop item.' }
$visibility = if ($stagedProps -match 'visibility=(\w+)') { $Matches[1] } else { 'unknown' }
$hasId = $stagedProps -match 'workshop-id=\d+'
Ok "item staged at $item (visibility=$visibility, $(if ($hasId) { 'updating existing item' } else { 'will CREATE a new item' }))"

# --- upload ----------------------------------------------------------------
if (-not $Upload) {
    Step 'Staging complete'
    Write-Host "To upload later, run from bin\:" -ForegroundColor Cyan
    Write-Host "  java -cp hafen.jar haven.SteamWorkshop upload $item"
    if ($Message) { Write-Host "  java -cp hafen.jar haven.SteamWorkshop upload $item $Message" }
    exit 0
}

# Smoke test: launch the staged item the way Steam does, make sure it doesn't crash.
if (-not $SkipSmoke) {
    Step 'Smoke-testing the staged item (Steam launch path)'
    # The actual test is smoke-steam-launch.ps1: it drives the real launcher.jar the way
    # Steam does, captures the javaw child's command line and re-runs it with java.exe so
    # stderr is readable.
    #
    # This used to call `haven.SteamWorkshop smoke-test` - a subcommand that has never
    # existed. The uploader answered "invalid workshop command: smoke-test" and exited
    # non-zero, so the gate failed on every run and the only way to publish was
    # -SkipSmoke. A check that always fails protects nothing; this one was added after a
    # broken item shipped, so it is worth having actually connected. The exit codes below
    # (0 pass / 1 fail / 2 could-not-run) were already written for this script.
    & (Join-Path $PSScriptRoot 'smoke-steam-launch.ps1') -Item $item
    switch ($LASTEXITCODE) {
        0 { Ok 'smoke test passed' }
        2 { Die 'Smoke test could not run (see above). Fix it, or pass -SkipSmoke to upload anyway.' }
        default { Die 'Smoke test FAILED - refusing to upload a client that crashes on startup.' }
    }
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

Ok 'Upload finished.'
if (-not $hasId) {
    Warn 'This was a NEW item - copy the "workshop-id=..." line the tool printed above into'
    Warn 'steam\workshop-client.properties (then commit it) so future uploads update this same item.'
}