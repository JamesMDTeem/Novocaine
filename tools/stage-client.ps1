<#
.SYNOPSIS
    Stage the runnable client out of bin\ into a clean directory that contains nothing
    personal - the one definition of "what ships", shared by the GitHub release and the
    Steam Workshop item.

.DESCRIPTION
    bin\ is the LIVE game install, not build output. Alongside the client it accumulates
    whatever the running client learned: the crew bots' map of the world (botmap.json),
    the areas drawn on it (botplaces.json, botbarriers.json), work claims, LP logs, the
    alchemy book extract, session logs, and any pre-merge backup taken by hand. All of
    that is one player's world state, and some of it (a botplaces anchor is a segment id
    and a pair of world coordinates) is a map to their base.

    Both packaging scripts used to copy bin\* and then delete four known-bad names. That
    is backwards: it ships anything nobody remembered to name, which is exactly how
    botmap.json, botplaces.json, botbarriers.json, botclaims\ and _premerge-backup-*
    ended up in published releases. This script inverts it - only recognised, shippable
    files are copied, and anything unrecognised is reported instead of silently included.
    A new state file the client starts writing tomorrow therefore stays home by default.

    The shippable set mirrors build.xml's `bin` target (the files the build itself puts
    in bin\), plus the two learned-but-impersonal sqlite caches. Keep them in step.

    manifest.json is REGENERATED over the staged tree rather than copied. The one the
    build leaves in bin\ hashes bin\ - state files included - so shipping it would both
    name the files we just excluded and make verification fail against the zip.

.PARAMETER Destination
    Directory to stage into. Created if missing, emptied if it already exists.

.PARAMETER RepoRoot
    Repository root. Defaults to this script's parent directory.

.PARAMETER NoManifest
    Don't generate manifest.json. For callers that add more files to the stage afterwards
    (the release adds the updater and BUILD-INFO.txt; the Steam item overlays its own
    metadata) and so must generate it themselves once the tree is final.

.PARAMETER Quiet
    Suppress the per-category summary; warnings about unrecognised files still print.

.OUTPUTS
    The staged directory's path.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Destination,
    [string]$RepoRoot,
    [switch]$NoManifest,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
if (-not $RepoRoot) { $RepoRoot = Split-Path $PSScriptRoot -Parent }
$bin = Join-Path $RepoRoot 'bin'
if (-not (Test-Path $bin)) { throw "No bin\ directory at $bin - build first." }

function Note($m) { if (-not $Quiet) { Write-Host "    $m" -ForegroundColor Green } }
function Warn($m) { Write-Host "    $m" -ForegroundColor Yellow }

# --- what ships -------------------------------------------------------------
# Wildcards are matched against the entry name at the top level of bin\ only.
$shipPatterns = @(
    '*.jar'                     # the client and every Class-Path entry, incl. hafen-panama
    '*.hl'                      # hafen.hl / launcher.hl launcher descriptors
    'Play.bat', 'Play_NoConsole.bat', 'Play_Linux.sh', 'Play_WithSteam.bat', 'Play_WithSteam_NoConsole.bat'
    'res', 'AlarmSounds', 'midiFiles', 'MapIconsPresets'
    'haven-config.properties'
    'workshop-client.properties', 'steam_appid.txt', 'steamicon.gif'
    'hitboxes.db'               # resource-name -> collision polygon; global game data
    'static_data.db'            # FlowerMenu's petal cache; global game data
)

# --- what never ships -------------------------------------------------------
# Recognised runtime state. Listing it explicitly (rather than relying on the catch-all)
# keeps the summary honest about what was withheld and why.
$statePatterns = @(
    'logs'                      # session logs, incl. chat
    'lp'                        # per-character LP discovery logs
    'botclaims'                 # crew work-slot reservations
    'botmap.json'               # learned terrain for one world
    'botplaces.json'            # drawn areas; anchors are real world coordinates
    'botbarriers.json'          # learned walls and gates; likewise
    'alchemy-book-dump.json'    # extract of one character's alchemy book
    'manifest.json'             # regenerated over the stage below, never copied
    '_premerge-backup*', '.pre-update-backup*'
    '*.log', '*.tmp', 'Hurricane-prefs.xml'
)

function Matches-Any($name, $patterns) {
    foreach ($p in $patterns) { if ($name -like $p) { return $true } }
    return $false
}

# --- stage ------------------------------------------------------------------
if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Destination | Out-Null

$shipped = @(); $withheld = @(); $unknown = @()
foreach ($entry in Get-ChildItem -LiteralPath $bin -Force) {
    if (Matches-Any $entry.Name $statePatterns) { $withheld += $entry.Name; continue }
    if (Matches-Any $entry.Name $shipPatterns) {
        Copy-Item -LiteralPath $entry.FullName -Destination $Destination -Recurse -Force
        $shipped += $entry.Name
        continue
    }
    $unknown += $entry.Name
}

# --- the client must still be runnable --------------------------------------
# The allowlist can only drop something needed if build.xml grows a file this script has
# not been told about, so check the pieces without which the zip is not a client at all.
$required = @('hafen.jar', 'builtin-res.jar', 'hafen-res.jar', 'Play.bat', 'res')
$missing = @($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $Destination $_)) })
if ($missing.Count -gt 0) {
    throw "Staging dropped files the client cannot run without: $($missing -join ', '). " +
          "If build.xml added them, add them to `$shipPatterns in tools\stage-client.ps1."
}

# --- manifest over the staged tree ------------------------------------------
# haven.BuildManifest lives in hafen.jar, so this needs no build\classes.
if (-not $NoManifest) {
    $manifest = Join-Path $Destination 'manifest.json'
    & java -cp (Join-Path $bin 'hafen.jar') haven.BuildManifest $Destination $manifest
    if ($LASTEXITCODE -ne 0) { throw 'Failed to regenerate manifest.json over the staged client.' }
}

# --- report -----------------------------------------------------------------
Note "staged $($shipped.Count) entries into $Destination"
if ($withheld.Count -gt 0) { Note "withheld local state: $($withheld -join ', ')" }
if ($unknown.Count -gt 0) {
    Warn "NOT shipped - unrecognised entries in bin\: $($unknown -join ', ')"
    Warn 'If one of those belongs in a release, add it to $shipPatterns in tools\stage-client.ps1.'
}

return $Destination
