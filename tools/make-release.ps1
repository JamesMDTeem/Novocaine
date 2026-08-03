<#
.SYNOPSIS
    Build Novocaine and publish a ready-to-play GitHub Release that friends can download,
    extract, and run without installing a JDK or Ant (a JRE 17-21 is all they need).

.DESCRIPTION
    A GitHub Release is the friend-facing distribution channel: the runnable client is the
    built bin\ directory (hafen.jar, Play.bat, resources, natives - ~170MB), zipped and
    attached as a release asset. This script does the whole cycle:

        1. Build a clean client (delegates to build-and-play.ps1 -NoLaunch,
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
    & "$repoRoot\build-and-play.ps1" -NoLaunch
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
Copy-Item -Path 'bin\*' -Destination $stage -Recurse -Force
# Local per-character/session state shouldn't ship.
foreach ($junk in @('logs', 'lp', 'alchemy-book-dump.json', '.pre-update-backup')) {
    $p = Join-Path $stage $junk
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}
# Build metadata so the release asset is reproducible from the zip alone.
$meta = @"
Build: $tag
Hurricane base: $hurricaneBase
Commit: $(git rev-parse HEAD)
Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
"@
[IO.File]::WriteAllText((Join-Path $stage 'BUILD-INFO.txt'), $meta, [Text.UTF8Encoding]::new($false))
# Zip it up (Compress-Archive on 5.1 is slow and sometimes misses empty dirs; cmd zip is fine).
$eapSaved = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
cmd /c "cd /d `"$dist`" && zip -r `"Novocaine-$Version.zip`" Novocaine > nul"
$ErrorActionPreference = $eapSaved
if ($LASTEXITCODE -ne 0) { Die 'zip failed.' }
if (-not (Test-Path $zip)) { Die "zip not created at $zip" }
Ok "zip ready: $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"

# --- release notes ---------------------------------------------------------
$notesFile = Join-Path $dist "release-notes-$Version.txt"
if ($Notes) {
    [IO.File]::WriteAllText($notesFile, $Notes, [Text.UTF8Encoding]::new($false))
} else {
    Step 'Generating release notes from commit history'
    $prevNova = git tag --list 'nova-*' --sort=-v:refname | Select-Object -First 1
    if ($prevNova) {
        $range = "$prevNova..HEAD"
    } else {
        $range = 'vendor-baseline..HEAD'
    }
    $subjects = git log --format='- %s' $range
    $notesBody = @"
Novocaine $Version

Based on Hurricane $hurricaneBase.

Changes since $prevNova:
$subjects
"@
    [IO.File]::WriteAllText($notesFile, $notesBody, [Text.UTF8Encoding]::new($false))
}

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

Ok "Done. Release: https://github.com/$Repo/releases/tag/$tag"
if ($Draft) { Warn 'Created as a DRAFT - publish it from the GitHub Releases page when ready.' }