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
# Only shippable files, never the running client's world state - see tools\stage-client.ps1.
# The old copy-everything-then-delete-four-names approach published one player's botmap,
# drawn areas, work claims and a hand-taken pre-merge backup to everyone who downloaded a
# release. Staging also regenerates manifest.json over the staged tree, which is what the
# delta packaging below and Update.ps1's post-apply verification both key off.
& (Join-Path $PSScriptRoot 'stage-client.ps1') -Destination $stage -RepoRoot $repoRoot -NoManifest | Out-Null

# The self-updater, so friends stop re-downloading the whole client by hand. GitHub-release
# only: a Workshop install is updated by Steam, and Update.ps1 refuses to touch one.
Copy-Item -Path (Join-Path $repoRoot 'launcher\*') -Destination $stage -Force
# Build metadata so the release asset is reproducible from the zip alone.
$meta = @"
Build: $tag
Channel: github
Hurricane base: $hurricaneBase
Commit: $(git rev-parse HEAD)
Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
"@
[IO.File]::WriteAllText((Join-Path $stage 'BUILD-INFO.txt'), $meta, [Text.UTF8Encoding]::new($false))

# manifest.json last, so it covers Update.ps1/Update.bat and BUILD-INFO.txt too. It has to:
# Update.ps1 verifies exactly what the manifest names, and a delta is exactly the files
# whose hash moved - if BUILD-INFO.txt were missing from it, an updated install would keep
# reporting the old version and never see the next release.
$stagedManifest = Join-Path $stage 'manifest.json'
& java -cp (Join-Path $repoRoot 'bin\hafen.jar') haven.BuildManifest $stage $stagedManifest
if ($LASTEXITCODE -ne 0) { Die 'Failed to generate manifest.json for the release.' }
# Zip it up. This used to shell out to `zip`, which is not installed here and is not a
# Windows built-in, so packaging died at this line every time. .NET's ZipFile exists on any
# PS 5.1 box and does not have Compress-Archive's empty-directory problem. The staging dir
# is moved under a throwaway parent first so the archive keeps its top-level Novocaine\
# folder and still extracts cleanly.
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
$pack = Join-Path $dist '_pkg'
if (Test-Path -LiteralPath $pack) { Remove-Item -LiteralPath $pack -Recurse -Force }
New-Item -ItemType Directory -Force -Path $pack | Out-Null
Move-Item -LiteralPath $stage -Destination (Join-Path $pack 'Novocaine')
try {
    [IO.Compression.ZipFile]::CreateFromDirectory(
        $pack, $zip, [IO.Compression.CompressionLevel]::Optimal, $false)
} catch {
    Die "zip failed: $($_.Exception.Message)"
}
# Move it back rather than deleting: the delta below diffs the staged tree.
Move-Item -LiteralPath (Join-Path $pack 'Novocaine') -Destination $stage
Remove-Item -LiteralPath $pack -Recurse -Force
if (-not (Test-Path $zip)) { Die "zip not created at $zip" }
Ok "zip ready: $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"

# --- delta package ---------------------------------------------------------
# The full client is ~170MB and almost all of it is res/ and the vendored jars, which do
# not change between our releases; a typical update is hafen.jar and little else. So we
# also publish just the files whose hash moved since the previous release, and Update.ps1
# prefers it. Requires the previous release to have published a manifest.json asset - the
# first release that does simply has no delta, which the updater handles by falling back.
$deltaZip = $null
$prevTag = git tag --list 'nova-*' --sort=-v:refname | Where-Object { $_ -ne $tag } | Select-Object -First 1
if ($prevTag) {
    Step "Building a delta against $prevTag"
    $prevDir = Join-Path $dist '_prevmanifest'
    if (Test-Path -LiteralPath $prevDir) { Remove-Item -LiteralPath $prevDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $prevDir | Out-Null
    $eapSaved = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    gh release download $prevTag --repo $Repo --pattern 'manifest.json' --dir $prevDir 1>$null 2>$null
    $gotPrev = ($LASTEXITCODE -eq 0)
    $ErrorActionPreference = $eapSaved

    if (-not $gotPrev) {
        Warn "$prevTag published no manifest.json - no delta this time (full download only)."
    } else {
        $prevMap = @{}
        foreach ($f in (Get-Content (Join-Path $prevDir 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json).files) { $prevMap[$f.path] = $f.sha256 }
        $newMap = @{}
        foreach ($f in (Get-Content $stagedManifest -Raw -Encoding UTF8 | ConvertFrom-Json).files) { $newMap[$f.path] = $f.sha256 }

        $changed = @($newMap.Keys | Where-Object { $prevMap[$_] -ne $newMap[$_] })
        $removed = @($prevMap.Keys | Where-Object { -not $newMap.ContainsKey($_) })

        $dwork = Join-Path $dist '_delta'
        if (Test-Path -LiteralPath $dwork) { Remove-Item -LiteralPath $dwork -Recurse -Force }
        New-Item -ItemType Directory -Force -Path (Join-Path $dwork 'payload') | Out-Null
        foreach ($rel in $changed) {
            $src = Join-Path $stage ($rel -replace '/', '\')
            $dst = Join-Path (Join-Path $dwork 'payload') ($rel -replace '/', '\')
            $parent = Split-Path $dst -Parent
            if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
            Copy-Item -LiteralPath $src -Destination $dst -Force
        }
        # manifest.json is never in $changed - BuildManifest leaves its own output out of the
        # listing it writes - but the install's copy has to move forward with the rest, or
        # Update.ps1 verifies the new files against the previous release's hashes and falls
        # back to the full download every single time.
        Copy-Item -LiteralPath $stagedManifest -Destination (Join-Path $dwork 'payload\manifest.json') -Force
        [IO.File]::WriteAllText((Join-Path $dwork 'removed.txt'), (($removed -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $dwork 'delta-info.txt'), "from=$prevTag`nto=$tag`nchanged=$($changed.Count)`nremoved=$($removed.Count)`n", [Text.UTF8Encoding]::new($false))

        $prevVersion = $prevTag -replace '^nova-', ''
        $deltaZip = Join-Path $dist "Novocaine-$Version-from-$prevVersion.zip"
        if (Test-Path -LiteralPath $deltaZip) { Remove-Item -LiteralPath $deltaZip -Force }
        [IO.Compression.ZipFile]::CreateFromDirectory($dwork, $deltaZip, [IO.Compression.CompressionLevel]::Optimal, $false)
        Remove-Item -LiteralPath $dwork -Recurse -Force
        Ok "delta ready: $($changed.Count) changed, $($removed.Count) removed ($([math]::Round((Get-Item $deltaZip).Length / 1MB, 1)) MB)"
    }
    Remove-Item -LiteralPath $prevDir -Recurse -Force -ErrorAction SilentlyContinue
}

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

Changes since ${prevNova}:
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

# manifest.json rides along as its own asset: it is how the NEXT release works out what
# changed, and it is small enough that Update.ps1 could fetch it on its own.
$assets = @($zip, $stagedManifest)
if ($deltaZip) { $assets += $deltaZip }

if ($exists) {
    Warn "Release $tag already exists - updating its notes and replacing its assets."
    & gh release edit $tag --repo $Repo --title $title --notes-file $notesFile
    & gh release upload $tag @assets --repo $Repo --clobber
} else {
    $ghArgs = @('release', 'create', $tag) + $assets + @('--repo', $Repo, '--title', $title, '--notes-file', $notesFile)
    if ($Draft) { $ghArgs += '--draft' }
    & gh @ghArgs
}

Ok "Done. Release: https://github.com/$Repo/releases/tag/$tag"
if ($Draft) { Warn 'Created as a DRAFT - publish it from the GitHub Releases page when ready.' }