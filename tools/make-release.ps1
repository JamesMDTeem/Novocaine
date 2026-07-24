<#
.SYNOPSIS
    Build Novocaine and publish a ready-to-play GitHub Release that friends can download,
    extract, and run without installing a JDK or Ant (a JRE 17-21 is all they need).

.DESCRIPTION
    A GitHub Release is the friend-facing distribution channel: the runnable client is the
    built bin\ directory (hafen.jar, Play.bat, resources, natives - ~170MB), zipped and
    attached as a release asset. This script does the whole cycle:

        1. Build a clean client (delegates to update-and-play.ps1 -SkipUpdate -NoLaunch,
           so the same toolchain resolution and alchemy-present sanity check apply),
           unless -SkipBuild is given and bin\hafen.jar already exists.
        2. Zip bin\ into dist\Novocaine-<Version>.zip, under a top-level Novocaine\ folder
           so it extracts cleanly.
        3. Create (or update) the GitHub Release for the tag and attach the zip.

    Friends then: download the zip -> extract -> run Novocaine\Play.bat.

.PARAMETER Version
    Release version, used for the git tag (nova-<Version>), the zip name and the release
    title. Defaults to a date stamp (yyyy.MM.dd) so releases sort chronologically without
    having to track a build counter. Override for semver-style versions, e.g. -Version 0.1.0.

.PARAMETER Notes
    Release notes body. If omitted, notes are auto-generated from the commit subjects since
    the previous nova-* tag (or the whole fork if this is the first release).

.PARAMETER Draft
    Create the release as a draft (not visible to anyone until you publish it from GitHub).
    Recommended for the first release so you can eyeball it before it goes live.

.PARAMETER SkipBuild
    Skip the build and zip whatever is already in bin\. Use only when you just built.

.PARAMETER Repo
    owner/name of the GitHub repo. Defaults to JamesMDTeem/Novocaine.

.EXAMPLE
    .\tools\make-release.ps1 -Draft
    .\tools\make-release.ps1 -Version 0.1.0 -Notes "First public build."
#>

[CmdletBinding()]
param(
    [string]$Version,
    [string]$Notes,
    [switch]$Draft,
    [switch]$SkipBuild,
    [string]$Repo = 'JamesMDTeem/Novocaine'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host "`n!! $m" -ForegroundColor Red; exit 1 }

# --- preconditions ---------------------------------------------------------
Step 'Checking prerequisites'
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Warn 'GitHub CLI (gh) not found. Install it from https://cli.github.com/ and run "gh auth login".'
    Die 'gh not found.'
}
# Native stderr must not be redirected into PowerShell's error stream here: under
# $ErrorActionPreference='Stop', PS 5.1 wraps redirected native stderr in a terminating
# ErrorRecord even on success. Relax EAP locally and key off the exit code instead.
$eapSaved = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
gh auth status 1>$null 2>$null
$authed = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $eapSaved
if (-not $authed) { Die 'gh is not authenticated. Run "gh auth login" first.' }
Ok 'gh present and authenticated'

$dirty = git status --porcelain --untracked-files=no
if ($dirty) {
    Warn 'Working tree has uncommitted changes - the release will build from the WORKING TREE,'
    Warn 'not from a clean commit. Commit first if you want the release to match a pushed state.'
}

# --- version / tag ---------------------------------------------------------
if (-not $Version) { $Version = Get-Date -Format 'yyyy.MM.dd' }
$tag = "nova-$Version"
$title = "Novocaine $Version"
# The Hurricane release this build is based on, for the release title/notes.
$baseSubject = (git log -1 --format=%s vendor-baseline)
$hurricaneBase = if ($baseSubject -match 'Hurricane (\S+) source baseline') { $Matches[1] } else { 'unknown' }
Ok "tag = $tag   (Hurricane base: $hurricaneBase)"

# --- build -----------------------------------------------------------------
if ($SkipBuild) {
    Step 'Skipping build (-SkipBuild)'
    if (-not (Test-Path 'bin\hafen.jar')) { Die 'bin\hafen.jar missing - cannot skip the build.' }
} else {
    Step 'Building a clean client'
    & "$repoRoot\update-and-play.ps1" -SkipUpdate -NoLaunch
    if ($LASTEXITCODE -ne 0) { Die 'Build failed; no release made.' }
}
if (-not (Test-Path 'bin\hafen.jar')) { Die 'bin\hafen.jar not found after build.' }

# --- package ---------------------------------------------------------------
Step 'Packaging bin\ into a release zip'
$dist = Join-Path $repoRoot 'dist'
$stage = Join-Path $dist 'Novocaine'
$zip = Join-Path $dist "Novocaine-$Version.zip"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
# Copy the runnable client under a Novocaine\ top folder so the zip extracts to one tidy dir.
Copy-Item -Path 'bin\*' -Destination $stage -Recurse -Force
# Local per-character/session state that shouldn't ship in a distributable build.
foreach ($junk in @('logs', 'lp', 'alchemy-book-dump.json', '.pre-update-backup')) {
    $p = Join-Path $stage $junk
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $stage -DestinationPath $zip -CompressionLevel Optimal
$zipMB = '{0:N1} MB' -f ((Get-Item $zip).Length / 1MB)
Ok "zip = $zip ($zipMB)"

# --- notes -----------------------------------------------------------------
if (-not $Notes) {
    $prevTag = (git tag --list 'nova-*' --sort=-creatordate | Where-Object { $_ -ne $tag } | Select-Object -First 1)
    $range = if ($prevTag) { "$prevTag..HEAD" } else { 'vendor-baseline..HEAD' }
    $log = (git log --no-merges --format='- %s' $range | Where-Object { $_ -notmatch 'source baseline' }) -join "`n"
    if (-not $log) { $log = '- Maintenance build.' }
    $Notes = @"
Novocaine custom Haven & Hearth client, based on Hurricane $hurricaneBase.

**How to play:** download ``Novocaine-$Version.zip`` below, extract it, and run
``Novocaine\Play.bat``. You need Java 17-21 installed (a JRE is enough) -
https://adoptium.net/temurin/releases/?version=21 if you don't have it.

**Changes in this build:**
$log
"@
}
$notesFile = Join-Path $env:TEMP "novocaine-relnotes-$Version.md"
# .NET's WriteAllText is UTF-8 WITHOUT a BOM; PS 5.1's `Out-File -Encoding utf8` adds one,
# which GitHub then renders as a stray char at the top of the release body.
[System.IO.File]::WriteAllText($notesFile, $Notes)

# --- publish ---------------------------------------------------------------
Step "Publishing GitHub Release $tag"
# Same native-stderr caveat as the auth check above - relax EAP for the existence probe.
$eapSaved = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
gh release view $tag --repo $Repo 1>$null 2>$null
$exists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $eapSaved

if ($exists) {
    Warn "Release $tag already exists - updating its notes and replacing the zip asset."
    & gh release edit $tag --repo $Repo --title $title --notes-file $notesFile
    & gh release upload $tag $zip --repo $Repo --clobber
} else {
    $ghArgs = @('release', 'create', $tag, $zip, '--repo', $Repo, '--title', $title, '--notes-file', $notesFile)
    if ($Draft) { $ghArgs += '--draft' }
    & gh @ghArgs
}
if ($LASTEXITCODE -ne 0) { Die 'gh release publish failed.' }

Ok "Done. Release: https://github.com/$Repo/releases/tag/$tag"
if ($Draft) { Warn 'Created as a DRAFT - publish it from the GitHub Releases page when ready.' }
