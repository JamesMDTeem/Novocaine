<#
.SYNOPSIS
    Reviewed upstream-import helper for the Novocaine fork.

.DESCRIPTION
    master is the fork trunk. This script brings in changes from the three upstream
    lineages - deliberately, one at a time, for review - instead of the old auto-update
    flow that re-applied the whole fork as a patch.

    Upstream remotes (added automatically if missing):
      upstream  -> Nightdawg/Hurricane       (the Hurricane lineage we forked from)
      hafen     -> dolda2000/hafen-client    (original Vanilla client, upstream of Hurricane)
      nurgling  -> aleksandrsvoboda/nurgling2 (another active fork with useful features)

    Modes:
      -List        Fetch all remotes, show the latest tags/commits on each, and whether
                   they are newer than the fork's last-known baseline.
      -Diff <tag>  Fetch the tag and print the source delta (-- src build.xml) between
                   the current baseline and that tag. Pure read - no write.
      -Import <tag>  Review-gated merge of an upstream tag.
                   1. Fetches the tag.
                   2. Computes the source delta: git diff <last-baseline>..<tag> -- src build.xml
                   3. Applies it with git apply --3way (conflicts stop here for hand resolution).
                   4. Refreshes untracked payload (res/, lib/, Release/) from the tag's tree
                      via git archive | tar so it stays UNTRACKED (matching .gitignore).
                   6. Stages source changes with git add; prints a summary. Does NOT auto-commit
                      (optionally -Commit to do so with a conventional message).
      -Pick <remote> <sha>  Cherry-pick a specific commit from hafen/nurgling.
                   Fetches the remote, then tries `git cherry-pick <sha>`. If that fails
                   (trees too far apart), falls back to `git show <sha> | git apply --3way`.
                   Leaves the result unstaged for review. No auto-commit.
      -ForkDiff    Convenience view of what the fork changed vs upstream:
                   git diff --stat vendor-baseline..HEAD. This replaces the "fork patch"
                   mental model with a live, always-current reference.

    The vendor-baseline and alchemy tags are now static historical markers - they are
    never moved by this script. -ForkDiff uses vendor-baseline..HEAD (master tip) to
    show the actual current fork delta.

.PARAMETER List
    List available upstream tags/commits.

.PARAMETER Diff
    Show the source delta between the current baseline and the given upstream tag.

.PARAMETER Import
    Import (merge) the given upstream tag after review.

.PARAMETER Pick
    Cherry-pick a specific commit from hafen or nurgling (requires -Remote too).

.PARAMETER Remote
    Remote name for -Pick (hafen or nurgling).

.PARAMETER Commit
    With -Import, auto-commit the staged source changes with a conventional message.
    Without it, you review `git diff --staged` and commit manually.

.PARAMETER ForkDiff
    Show the full fork delta vs vendor-baseline.

.EXAMPLE
    .\tools\merge-upstream.ps1 -List
    .\tools\merge-upstream.ps1 -Diff v1.67
    .\tools\merge-upstream.ps1 -Import v1.68 -Commit
    .\tools\merge-upstream.ps1 -Pick abc1234 -Remote hafen
    .\tools\merge-upstream.ps1 -ForkDiff
#>

[CmdletBinding(DefaultParameterSetName='None')]
param(
    [Parameter(ParameterSetName='List')][switch]$List,
    [Parameter(ParameterSetName='Diff', Mandatory=$true)][string]$Diff,
    [Parameter(ParameterSetName='Import', Mandatory=$true)][string]$Import,
    [Parameter(ParameterSetName='Pick', Mandatory=$true)][string]$Pick,
    [Parameter(ParameterSetName='Pick', Mandatory=$true)][ValidateSet('hafen','nurgling','apricot','kami')][string]$Remote,
    [Parameter(ParameterSetName='Import')][switch]$Commit,
    [Parameter(ParameterSetName='ForkDiff')][switch]$ForkDiff
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "    $msg" -ForegroundColor Yellow }
function Die($msg)  { Write-Host "`n!! $msg" -ForegroundColor Red; exit 1 }

# --- ensure remotes --------------------------------------------------------
Step 'Ensuring remotes'
$remotes = @{
    'upstream' = 'https://github.com/Nightdawg/Hurricane.git'
    'hafen'    = 'https://github.com/dolda2000/hafen-client.git'
    'nurgling' = 'https://github.com/aleksandrsvoboda/nurgling2.git'
}
$existing = git remote
foreach ($name in $remotes.Keys) {
    if ($existing -notcontains $name) {
        git remote add $name $remotes[$name] | Out-Null
        Ok "added remote '$name' -> $($remotes[$name])"
    } else {
        Ok "remote '$name' already present"
    }
}

# --- common: fetch all -----------------------------------------------------
Step 'Fetching upstream remotes (upstream, hafen, nurgling)'
# Skip origin (our fork) - its tags like vendor-baseline would clobber local copies.
foreach ($name in @('upstream','hafen','nurgling')) {
    git fetch $name --tags --prune
    if ($LASTEXITCODE -ne 0) { Die "git fetch $name failed." }
}
Ok 'fetch complete'

# --- baseline tag detection ------------------------------------------------
# The "last-known baseline" is vendor-baseline (the snapshot of upstream v1.67 source).
# In the merge model, vendor-baseline stays fixed as the historical reference point.
$baselineTag = 'vendor-baseline'
if (-not (git rev-parse -q --verify "refs/tags/$baselineTag")) {
    Die "Baseline tag '$baselineTag' not found. This fork expects vendor-baseline to exist as the historical upstream v1.67 snapshot."
}

# --- -List -----------------------------------------------------------------
if ($List) {
    Step 'Upstream status'
    foreach ($name in @('upstream','hafen','nurgling')) {
        Write-Host "`n[$name] $($remotes[$name])" -ForegroundColor Cyan
        # Show newest 10 version tags (filter out non-version tags like "updater")
        $tags = git ls-remote --tags $name | Select-String 'refs/tags/' | ForEach-Object {
            $_ -replace '.*refs/tags/', '' -replace '\^{}$', ''
        } | Where-Object { $_ -match '^v?\d+\.\d+(\.\d+)?(-.*)?$' } | Sort-Object { [version]($_ -replace '^v','') } -Descending | Select-Object -First 10
        if ($tags) {
            Write-Host "  Latest tags: $($tags -join ', ')"
        }
        # Show HEAD commit
        $head = git ls-remote $name HEAD | ForEach-Object { $_.Split()[0] }
        if ($head) {
            Write-Host "  HEAD: $head"
        }
        # Compare baseline tag if it exists on this remote
        $remoteBaseline = git ls-remote --tags $name "refs/tags/$baselineTag" | ForEach-Object { $_.Split()[0] }
        if ($remoteBaseline) {
            $localBase = git rev-parse $baselineTag
            if ($remoteBaseline -ne $localBase) {
                Warn "  $name/$baselineTag ($remoteBaseline) DIFFERS from local $baselineTag ($localBase)"
            } else {
                Ok "  $name/$baselineTag matches local"
            }
        }
    }
    exit 0
}

# --- -ForkDiff -------------------------------------------------------------
if ($ForkDiff) {
    Step "Fork delta (vendor-baseline..HEAD)"
    $range = "$baselineTag..HEAD"
    $stat = git diff --stat $range -- src build.xml
    if ($stat) {
        Write-Host $stat
    } else {
        Warn 'No source changes vs vendor-baseline (unexpected).'
    }
    $full = git diff $range -- src build.xml
    if ($full) {
        Write-Host "`nFull diff:" -ForegroundColor Cyan
        Write-Host $full
    }
    exit 0
}

# --- -Diff <tag> -----------------------------------------------------------
if ($Diff) {
    $tag = $Diff
    Step "Source delta: $baselineTag .. $tag"
    # Ensure the tag is fetched (fetch --all above should have it, but be safe)
    $tagRef = git rev-parse -q --verify "refs/tags/$tag"
    if (-not $tagRef) {
        Die "Tag '$tag' not found locally after fetch. Is it on one of the remotes?"
    }
    $stat = git diff --stat $baselineTag..$tag -- src build.xml
    if ($stat) {
        Write-Host $stat
    } else {
        Warn 'No source changes in this range.'
    }
    $full = git diff $baselineTag..$tag -- src build.xml
    if ($full) {
        Write-Host "`nFull diff:" -ForegroundColor Cyan
        Write-Host $full
    }
    exit 0
}

# --- -Import <tag> ---------------------------------------------------------
if ($Import) {
    $tag = $Import
    Step "Review-gated import of $tag"

    # 0. refuse if dirty
    $dirty = git status --porcelain --untracked-files=no
    if ($dirty) {
        Write-Host $dirty
        Die 'Working tree has uncommitted changes. Commit or stash first.'
    }

    # 1. fetch the tag (already done via fetch --all, but verify)
    $tagRef = git rev-parse -q --verify "refs/tags/$tag"
    if (-not $tagRef) {
        Die "Tag '$tag' not found locally. Is it on one of the remotes?"
    }
    Ok "tag $tag = $tagRef"

    # 2. compute source delta between baseline and tag
    Step "Computing source delta ($baselineTag..$tag -- src build.xml)"
    $patch = Join-Path $env:TEMP "upstream-import-$(Get-Date -Format yyyyMMdd-HHmmss).patch"
    cmd /c "git diff --binary $baselineTag..$tag -- src build.xml > `"$patch`""
    if (-not (Test-Path $patch) -or (Get-Item $patch).Length -eq 0) {
        Warn 'Source delta is empty - nothing to import.'
        exit 0
    }
    Ok "patch written: $patch ($(Get-Item $patch).Length bytes)"

    # 3. apply with --3way
    Step 'Applying source delta (git apply --3way)'
    # Note: cmd redirection keeps patch as raw bytes; PS redirection would add BOM.
    cmd /c "git apply --3way --stat `"$patch`""
    if ($LASTEXITCODE -ne 0) {
        cmd /c "git apply --3way --check `"$patch`" 2>&1"
        Die 'git apply --3way --check failed. Conflicts require hand resolution.'
    }
    cmd /c "git apply --3way `"$patch`""
    if ($LASTEXITCODE -ne 0) {
        Die 'git apply --3way failed. Resolve conflicts, then run again.'
    }
    Ok 'source delta applied'

    # 4. refresh untracked payload (res/, lib/, Release/) from tag's tree
    Step 'Refreshing untracked payload (res/, lib/, Release/) from tag tree'
    # git archive streams the tree; tar extracts. Both are in PATH on Windows (Git for Windows).
    $payloadDirs = 'res', 'lib', 'Release'
    foreach ($dir in $payloadDirs) {
        if (git ls-tree -r $tag --name-only | Select-String "^$dir/") {
            Write-Host "  extracting $dir/..."
            cmd /c "git archive $tag $dir | tar -x -C ."
            if ($LASTEXITCODE -ne 0) {
                Warn "tar extraction of $dir had issues (non-zero exit)."
            } else {
                Ok "  $dir/ refreshed"
            }
        } else {
            Warn "  $dir/ not present in tag $tag - skipping"
        }
    }

    # 5. stage source changes
    Step 'Staging source changes'
    git add src build.xml
    if ($LASTEXITCODE -ne 0) { Die 'git add failed.' }
    Ok 'source changes staged'

    # 6. summary
    Step 'Import summary'
    $stagedStat = git diff --staged --stat
    if ($stagedStat) { Write-Host $stagedStat }
    $stagedFiles = git diff --staged --name-only
    if ($stagedFiles) {
        Write-Host "`nStaged files:"
        Write-Host $stagedFiles
    }

    if ($Commit) {
        $msg = "Merge upstream $tag (source changes)"
        git commit -m $msg
        if ($LASTEXITCODE -eq 0) {
            Ok "Committed: $msg"
        } else {
            Die 'git commit failed.'
        }
    } else {
        Write-Host "`nReview the staged changes with:" -ForegroundColor Cyan
        Write-Host "  git diff --staged"
        Write-Host "`nThen commit manually, e.g.:" -ForegroundColor Cyan
        Write-Host "  git commit -m \"Merge upstream $tag (source changes)\""
    }
    exit 0
}

# --- -Pick <remote> <sha> --------------------------------------------------
if ($Pick) {
    $sha = $Pick
    Step "Cherry-picking $sha from $Remote"

    # 0. refuse if dirty
    $dirty = git status --porcelain --untracked-files=no
    if ($dirty) {
        Write-Host $dirty
        Die 'Working tree has uncommitted changes. Commit or stash first.'
    }

    # 1. fetch the remote
    Step "Fetching $Remote"
    git fetch $Remote
    if ($LASTEXITCODE -ne 0) { Die "git fetch $Remote failed." }

    # 2. try cherry-pick
    Step "Trying git cherry-pick $sha"
    git cherry-pick $sha
    if ($LASTEXITCODE -eq 0) {
        Ok 'cherry-pick succeeded'
    } else {
        Warn 'cherry-pick failed (likely tree too far apart). Falling back to git show | git apply --3way.'
        git cherry-pick --abort 2>$null
        $fallbackPatch = Join-Path $env:TEMP "pick-$sha-$(Get-Date -Format yyyyMMdd-HHmmss).patch"
        cmd /c "git show $sha > `"$fallbackPatch`""
        if (-not (Test-Path $fallbackPatch) -or (Get-Item $fallbackPatch).Length -eq 0) {
            Die "git show $sha produced empty patch."
        }
        cmd /c "git apply --3way --stat `"$fallbackPatch`""
        if ($LASTEXITCODE -ne 0) {
            cmd /c "git apply --3way --check `"$fallbackPatch`" 2>&1"
            Die 'git apply --3way --check failed on fallback patch. Conflicts require hand resolution.'
        }
        cmd /c "git apply --3way `"$fallbackPatch`""
        if ($LASTEXITCODE -ne 0) {
            Die 'git apply --3way failed on fallback patch. Resolve conflicts, then run again.'
        }
        Ok 'fallback apply succeeded'
    }

    Write-Host "`nResult left unstaged for review." -ForegroundColor Cyan
    Write-Host "  git diff        # see working-tree changes" -ForegroundColor Cyan
    Write-Host "  git diff --staged  # empty until you git add" -ForegroundColor Cyan
    Write-Host "`nThen commit when satisfied." -ForegroundColor Cyan
    exit 0
}

# --- no mode selected ------------------------------------------------------
Die 'No mode selected. Use -List, -Diff <tag>, -Import <tag> [-Commit], -Pick <remote> <sha>, or -ForkDiff.'