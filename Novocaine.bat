@echo off
rem The one entry point: double-click this to play. It updates an installed client to the
rem newest GitHub release, or builds a source checkout, and then starts the game with no
rem console window attached. Everything it does lives in Novocaine.ps1.
rem
rem   Novocaine.bat                 update-or-build, then play (ZGC by default)
rem   Novocaine.bat -Count 8        launch a crew of eight
rem   Novocaine.bat -NoLaunch       build only
rem   Novocaine.bat -Check          is there a newer release?
rem   Novocaine.bat -Console        with a console to read GC logs in
rem   Novocaine.bat -NoZGC -Console G1 instead of ZGC, with console (alias -G1)

setlocal
set "NOVOCAINE_HOME=%~dp0"
if "%NOVOCAINE_HOME:~-1%"=="\" set "NOVOCAINE_HOME=%NOVOCAINE_HOME:~0,-1%"
set "NOVOCAINE_PAUSE_ON_ERROR=1"

rem Clear the inherited module path so Windows PowerShell rebuilds its own default. If this
rem is launched from a PowerShell 7 session, PSModulePath arrives pointing at PS7's module
rem directories first, powershell.exe loads PS7's Microsoft.PowerShell.Utility instead of
rem its own, and cmdlets it cannot bind - Get-FileHash among them - simply vanish.
set "PSModulePath="

rem Novocaine.ps1 is copied to %TEMP% and run from there so that an update is free to
rem overwrite this folder - including Novocaine.ps1 itself - while it is running.
set "RUNNER=%TEMP%\Novocaine-running.ps1"
copy /y "%NOVOCAINE_HOME%\Novocaine.ps1" "%RUNNER%" >nul
if errorlevel 1 (
    echo Could not stage Novocaine.ps1 into %TEMP%.
    pause
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%RUNNER%" %*
set "RC=%ERRORLEVEL%"
del "%RUNNER%" >nul 2>&1
exit /b %RC%
