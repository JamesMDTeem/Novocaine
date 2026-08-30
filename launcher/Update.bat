@echo off
rem Shim. Updating and launching are the same thing now: Novocaine.bat checks GitHub for a
rem newer release, applies it, and starts the game. This file stays so that installs and
rem shortcuts that learned the name "Update.bat" keep working.
rem
rem   Update.bat -Check      report whether an update exists, install nothing
rem   Update.bat -NoLaunch   update only, do not start the game

cd /d "%~dp0"
call "%~dp0Novocaine.bat" %*
exit /b %ERRORLEVEL%
