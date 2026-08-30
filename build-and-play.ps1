<#
.SYNOPSIS
    Shim. The build/launch script is now Novocaine.ps1.

.DESCRIPTION
    Everything build-and-play.ps1 used to do lives in Novocaine.ps1, along with the
    launchers and the self-updater. This file stays because tools\make-release.ps1 and
    tools\make-steam-item.ps1 call it by name, and because it is in muscle memory.

    Deliberately no param block: $args holds the arguments exactly as typed, and array
    splatting re-reads the -Name tokens in it as named parameters. A
    ValueFromRemainingArguments parameter does NOT - it hands back bare strings, so
    `-NoLaunch` arrives as a positional value and binds to Novocaine.ps1's -Count.
#>

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Novocaine.ps1') @args
exit $LASTEXITCODE
