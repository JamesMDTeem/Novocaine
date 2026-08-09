@echo off
rem Update Novocaine to the newest GitHub release, then start the game.
rem Use this instead of Play.bat and you never have to download the client by hand again.
rem
rem Update.ps1 is copied to %TEMP% and run from there so that the update is free to
rem overwrite this folder - including Update.ps1 itself - while it is running.

setlocal
set "NOVOCAINE_HOME=%~dp0"
if "%NOVOCAINE_HOME:~-1%"=="\" set "NOVOCAINE_HOME=%NOVOCAINE_HOME:~0,-1%"

set "RUNNER=%TEMP%\Novocaine-Update-running.ps1"
copy /y "%NOVOCAINE_HOME%\Update.ps1" "%RUNNER%" >nul
if errorlevel 1 (
    echo Could not stage the updater into %TEMP%.
    pause
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%RUNNER%" %*
set "RC=%ERRORLEVEL%"
del "%RUNNER%" >nul 2>&1
exit /b %RC%
