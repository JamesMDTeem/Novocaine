@echo off
rem Run from this file's folder, so the client can be started from anywhere.
cd /d "%~dp0"
rem Generational ZGC instead of the default G1. Opt-in: this is a side-by-side
rem launcher, Play.bat is untouched and stays on G1.
rem
rem Measured on JDK 21.0.9 over a 45s client session (login screen, GC logging on):
rem   G1   106 pauses, 436.4ms total, max 12.73ms, 6 pauses over 10ms
rem   ZGC   90 pauses,   0.8ms total, max  0.02ms, 0 pauses over 5ms
rem A 60fps frame is 16.7ms, so G1 worst-case ate 76%% of one frame. The trade is
rem footprint: ZGC floated to 3632M before collecting where G1 peaked near 1515M.
rem
rem -XX:+ZGenerational is required on JDK 21 (default from 23, flag removed in 24),
rem so drop it if this client ever moves to a newer JDK.
set "JAVA=java"
if exist "%~dp0jre\bin\java.exe" set "JAVA=%~dp0jre\bin\java.exe"
"%JAVA%" -XX:+UseZGC -XX:+ZGenerational -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=false -jar hafen.jar
