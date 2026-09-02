<#
.SYNOPSIS
    Runs every check in the combat system and reports which passed.

.DESCRIPTION
    The combat work is spread across two languages and eight check harnesses, each with
    its own javac incantation buried in a file header. Retyping those is how a check
    stops being run, so this runs all of them and prints one table.

    Each Java harness is compiled on its own, from the smallest set of sources it needs.
    That is not just tidiness: haven.combat is supposed to import nothing from haven, and
    a harness that suddenly needs the whole client to compile is the alarm that the seam
    in ADR-0002 has gone. A compile failure here is a real finding, not a build problem.

    Exits 0 only if every check passes.

.PARAMETER Quiet
    Print only the summary table, not each harness's own output.

.EXAMPLE
    powershell -File tools\check-combat.ps1
    powershell -File tools\check-combat.ps1 -Quiet
#>
[CmdletBinding()]
param(
    [switch]$Quiet
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root

# JDK 21 specifically. A jre1.8 also exists on this machine and picking it up produces
# failures that look like source errors.
$jdk = 'C:\Program Files\Java\jdk-21'
if (-not (Test-Path $jdk)) {
    $jdk = $env:JAVA_HOME
}
$javac = Join-Path $jdk 'bin\javac.exe'
$java = Join-Path $jdk 'bin\java.exe'
if (-not (Test-Path $javac)) {
    Write-Host "no javac at $javac - set JAVA_HOME to a JDK 21" -ForegroundColor Red
    Pop-Location
    exit 2
}

$out = Join-Path $env:TEMP ('combatcheck-' + [System.Guid]::NewGuid().ToString('N'))
$results = @()

function Add-Result($name, $ok, $detail) {
    $script:results += [pscustomobject]@{ Check = $name; Passed = $ok; Detail = $detail }
}

function Invoke-JavaCheck($name, $sources, $mainClass, $sourcePath) {
    $dir = Join-Path $out $mainClass
    $null = New-Item -ItemType Directory -Force -Path $dir
    # A check that loads the data pack needs org.json on the source path. Everything else is
    # compiled from an explicit, minimal list, which is what keeps ADR-0002's seam honest.
    if ($sourcePath) {
        $compile = & $javac -nowarn -d $dir -sourcepath $sourcePath $sources 2>&1
    } else {
        $compile = & $javac -d $dir $sources 2>&1
    }
    if ($LASTEXITCODE -ne 0) {
        if (-not $Quiet) { $compile | ForEach-Object { Write-Host "    $_" } }
        Add-Result $name $false 'did not compile'
        return
    }
    $run = & $java -cp $dir $mainClass 2>&1
    $ok = ($LASTEXITCODE -eq 0)
    if (-not $Quiet) { $run | ForEach-Object { Write-Host "    $_" } }
    $last = ($run | Select-Object -Last 1)
    Add-Result $name $ok "$last"
}

function Invoke-PyCheck($name, $script) {
    $run = & python $script 2>&1
    $ok = ($LASTEXITCODE -eq 0)
    if (-not $Quiet) { $run | ForEach-Object { Write-Host "    $_" } }
    $last = ($run | Select-Object -Last 1)
    Add-Result $name $ok "$last"
}

$model = @(
    'src\haven\combat\Formulas.java',
    'src\haven\combat\Move.java',
    'src\haven\combat\Combatant.java',
    'src\haven\combat\Sim.java'
)

Write-Host "`n== the log schema" -ForegroundColor Cyan
Invoke-JavaCheck 'CombatLogCheck' (@('src\haven\combat\log\JsonObj.java',
    'src\haven\combat\log\Openings.java', 'src\haven\combat\log\CombatEvent.java',
    'src\haven\combat\log\CombatLogWriter.java', 'tools\CombatLogCheck.java')) 'CombatLogCheck'

Write-Host "`n== the model's arithmetic" -ForegroundColor Cyan
Invoke-JavaCheck 'CombatFormulaCheck' (@('src\haven\combat\Formulas.java',
    'tools\CombatFormulaCheck.java')) 'CombatFormulaCheck'

Write-Host "`n== the state machine" -ForegroundColor Cyan
Invoke-JavaCheck 'CombatSimCheck' ($model + @('tools\CombatSimCheck.java')) 'CombatSimCheck'

Write-Host "`n== the data pack, loaded the way the bot will load it" -ForegroundColor Cyan
Invoke-JavaCheck 'CombatPackCheck' ($model + @('src\haven\combat\data\Pack.java',
    'tools\CombatPackCheck.java')) 'CombatPackCheck' 'src'

Write-Host "`n== the Python follower, against the golden vectors" -ForegroundColor Cyan
Invoke-PyCheck 'model_check.py' 'tools\combat\model_check.py'

Write-Host "`n== what a log is allowed to measure" -ForegroundColor Cyan
Invoke-PyCheck 'fightlog_check.py' 'tools\combat\fightlog_check.py'

Write-Host "`n== the estimators" -ForegroundColor Cyan
Invoke-PyCheck 'estimate_check.py' 'tools\combat\estimate_check.py'

Write-Host "`n== the wiki data pack" -ForegroundColor Cyan
Invoke-PyCheck 'datapack_check.py' 'tools\combat\datapack_check.py'

Write-Host "`n== the client still builds" -ForegroundColor Cyan
$ant = 'C:\ant\apache-ant-1.10.17\bin\ant.bat'
if (Test-Path $ant) {
    $build = & $ant jar 2>&1
    $ok = ($build | Select-String -Quiet 'BUILD SUCCESSFUL')
    if (-not $Quiet) { $build | Select-String 'error|BUILD' | ForEach-Object { Write-Host "    $_" } }
    Add-Result 'ant jar' $ok (($build | Select-String 'BUILD' | Select-Object -Last 1))
} else {
    Add-Result 'ant jar' $false "no ant at $ant"
}

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue

Write-Host "`n"
$results | Format-Table -AutoSize @(
    @{ Label = 'check'; Expression = { $_.Check } },
    @{ Label = ' '; Expression = { if ($_.Passed) { 'ok' } else { 'FAILED' } } },
    @{ Label = 'result'; Expression = { ($_.Detail -replace '\s+', ' ').Trim() } }
)

$failed = @($results | Where-Object { -not $_.Passed })
Pop-Location
if ($failed.Count -gt 0) {
    Write-Host ("{0} of {1} checks FAILED" -f $failed.Count, $results.Count) -ForegroundColor Red
    exit 1
}
Write-Host ("all {0} checks passed" -f $results.Count) -ForegroundColor Green
exit 0
