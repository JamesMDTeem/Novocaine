<#
.SYNOPSIS
    Update Hurricane to the latest upstream release, re-apply the alchemy changes,
    rebuild, and launch.

.DESCRIPTION
    The fork is deliberately tiny -- one line in GameUI.java plus the alchemy package
    and its tools -- so it is kept as a patch between two tags rather than as a
    long-lived branch:

        vendor-baseline .. alchemy

    Updating therefore means: check out the upstream release, record it as a source
    baseline commit on master, re-apply that patch, re-tag, push to origin, rebuild.
    Nothing has to be merged by hand unless upstream edits the same line of
    GameUI.tick, which the script reports rather than guesses at.

    Upstream tracks the jars and resources this install also holds as untracked files,
    so checking out a release must overwrite them. Local databases are backed up first,
    because those accumulate state that is not in any release.

.PARAMETER Tag
    Release to move to. Defaults to the newest published release.

.PARAMETER SkipUpdate
    Rebuild and launch what is already checked out. Use after editing the fork.

.PARAMETER NoLaunch
    Update and build, but do not start the game.

.EXAMPLE
    .\update-and-play.ps1
    .\update-and-play.ps1 -SkipUpdate
    .\update-and-play.ps1 -Tag v1.67
#>

[CmdletBinding()]
param(
    [string]$Tag,
    [switch]$SkipUpdate,
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
if (-not $java) { Die 'java not found on PATH. Install JDK 21 and retry.' }
$env:JAVA_HOME = (Get-Item $java.Source).Directory.Parent.FullName
Ok "JAVA_HOME = $env:JAVA_HOME"

if (-not (Get-Command ant -ErrorAction SilentlyContinue)) {
    $antCandidates = @(Get-ChildItem 'C:\ant' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'bin' })
    $antBin = $antCandidates | Where-Object { Test-Path (Join-Path $_ 'ant.bat') } | Select-Object -First 1
    if (-not $antBin) { Die 'ant not found on PATH or under C:\ant. Install Apache Ant and retry.' }
    $env:PATH = "$env:PATH;$antBin"
    Ok "ant = $antBin"
} else {
    Ok 'ant already on PATH'
}

# --- update ----------------------------------------------------------------
if (-not $SkipUpdate) {
    Step 'Checking the fork is safe to move'

    # Uncommitted work would be destroyed by the checkout below. Refuse rather than
    # silently discard it; the fork itself lives in tags, not the working tree.
    $dirty = git status --porcelain --untracked-files=no
    if ($dirty) {
        Write-Host $dirty
        Die 'You have uncommitted changes. Commit them (and re-tag "alchemy") or stash, then retry.'
    }

    foreach ($t in @('vendor-baseline', 'alchemy')) {
        git rev-parse -q --verify "refs/tags/$t" > $null 2>&1
        if ($LASTEXITCODE -ne 0) { Die "Missing tag '$t'. The fork patch is defined as vendor-baseline..alchemy." }
    }
    Ok 'tags present'

    Step 'Resolving the target release'
    if (-not $Tag) {
        try {
            $latest = Invoke-RestMethod 'https://api.github.com/repos/Nightdawg/Hurricane/releases/latest' `
                -Headers @{ 'User-Agent' = 'hurricane-update-script' }
            $Tag = $latest.tag_name
        } catch {
            Die "Could not reach GitHub to find the latest release: $($_.Exception.Message)"
        }
    }
    Ok "target = $Tag"

    if ((git log -1 --format=%s vendor-baseline) -eq "Hurricane $Tag source baseline") {
        Step "Already based on $Tag"
        Ok 'nothing to update; building what is checked out'
    } else {

    Step 'Saving the fork as a patch'
    $patch = Join-Path $env:TEMP "novocaine-fork-$(Get-Date -Format yyyyMMdd-HHmmss).patch"
    # This script is included deliberately: it is tracked in the fork but absent from
    # upstream, so the checkout below would delete it. The patch puts it back.
    # cmd redirection keeps the patch as the raw bytes git emitted; PowerShell
    # redirection would re-encode it with a BOM, which git apply rejects.
    cmd /c "git diff vendor-baseline..alchemy -- src tools update-and-play.ps1 README.md > `"$patch`""
    if (-not (Test-Path $patch) -or (Get-Item $patch).Length -eq 0) { Die 'The fork patch came out empty.' }
    Ok "patch = $patch"

    Step 'Backing up local databases'
    # These accumulate state that no release contains, and the checkout would replace
    # them with the upstream copies.
    $backup = Join-Path $repo ".pre-update-backup\$(Get-Date -Format yyyyMMdd-HHmmss)"
    New-Item -ItemType Directory -Force -Path $backup | Out-Null
    foreach ($f in @('hitboxes.db', 'static_data.db', 'Release\hitboxes.db', 'Release\static_data.db')) {
        if (Test-Path $f) {
            $dest = Join-Path $backup $f
            New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
            Copy-Item $f $dest -Force; Ok "backed up $f"
        }
    }

    Step "Fetching $Tag from upstream"
    git remote get-url upstream > $null 2>&1
    if ($LASTEXITCODE -ne 0) {
        git remote add upstream https://github.com/Nightdawg/Hurricane.git
    }
    # Shallow: the full history carries every jar and resource ever committed.
    git fetch --depth=1 upstream "refs/tags/${Tag}:refs/tags/upstream-${Tag}" --force
    if ($LASTEXITCODE -ne 0) { Die "Could not fetch tag $Tag from upstream." }
    Ok 'fetched'

    Step "Checking out $Tag"
    # Forced: this install holds untracked copies of files the release tracks, and
    # they must be replaced for the update to mean anything.
    git checkout -f --detach "refs/tags/upstream-${Tag}"
    if ($LASTEXITCODE -ne 0) { Die "Checkout of $Tag failed." }
    Ok 'source tree now matches upstream'

    Step "Committing the $Tag source baseline on master"
    # The upstream fetch is shallow, so history built directly on it can never be
    # pushed to origin. Instead the release is recorded the way the original
    # "Hurricane 1.65 source baseline" was: a snapshot commit on our own master,
    # filtered by the fork's .gitignore so the binary payload (jars, res, Release)
    # stays as untracked working-directory files.
    git show master:.gitignore | Set-Content -Path .gitignore -Encoding Ascii
    $payload = Join-Path $env:TEMP 'novocaine-payload.list'
    cmd /c "git ls-files --cached --ignored --exclude-standard -z > `"$payload`""
    if ((Get-Item $payload).Length -gt 0) {
        git rm --cached -q --pathspec-from-file="$payload" --pathspec-file-nul
    }
    git add .gitignore
    $tree = git write-tree
    $baseline = git commit-tree $tree -p master -m "Hurricane $Tag source baseline"
    if (-not $baseline) { Die 'Could not create the baseline commit.' }
    # master is checked out, so it cannot be branch -f'd; point HEAD back at it and
    # reset. --mixed on purpose: the working tree (full payload) must stay put.
    git symbolic-ref HEAD refs/heads/master
    git reset -q $baseline
    Ok "baseline = $baseline"

    Step 'Re-applying the Novocaine fork'
    # --3way lets git resolve against blob context, so an upstream edit near our hook
    # still applies. A genuine clash stops here rather than producing a broken build.
    git apply --3way --whitespace=nowarn $patch
    if ($LASTEXITCODE -ne 0) {
        Warn 'The patch did not apply cleanly. Upstream has probably changed a line the fork touches.'
        Warn "Patch kept at: $patch"
        Warn 'Resolve the conflicts, then finish by hand:'
        Warn '  git add -- src tools update-and-play.ps1 README.md'
        Warn "  git commit -m `"Re-apply Novocaine fork on $Tag`""
        Warn "  git tag -f vendor-baseline $baseline ; git tag -f alchemy HEAD"
        Warn '  git push origin master ; git push -f origin vendor-baseline alchemy'
        Die 'Stopping before build so a half-patched client is never launched.'
    }
    git add -- src tools update-and-play.ps1 README.md
    git commit -q -m "Re-apply Novocaine fork on $Tag"
    git tag -f vendor-baseline $baseline
    git tag -f alchemy HEAD
    Ok 'fork re-applied; vendor-baseline and alchemy tags moved'

    Step 'Pushing to origin'
    # master only moves forward (each baseline's parent is the previous tip), but
    # the two workflow tags are rewritten on every update.
    git push -q origin master
    if ($LASTEXITCODE -ne 0) { Warn 'Could not push master; run "git push origin master" when back online.' }
    git push -q -f origin vendor-baseline alchemy
    if ($LASTEXITCODE -ne 0) { Warn 'Could not push tags; run "git push -f origin vendor-baseline alchemy" when back online.' }

    Step 'Restoring local databases'
    foreach ($f in @('hitboxes.db', 'static_data.db', 'Release\hitboxes.db', 'Release\static_data.db')) {
        $saved = Join-Path $backup $f
        if (Test-Path $saved) { Copy-Item $saved (Join-Path $repo $f) -Force; Ok "restored $f" }
    }

    }
} else {
    Step 'Skipping update (-SkipUpdate)'
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
    Ok 'alchemy classes compiled'
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
