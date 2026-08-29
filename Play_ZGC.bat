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
rem -XX:+ZGenerational is required on JDK 21 to get the generational collector. It was
rem made the default in 23 and REMOVED in 24 - on JDK 25/26 passing it is a fatal
rem "Unrecognized VM option" and the client will not start. IgnoreUnrecognizedVMOptions
rem makes this one line correct on both: JDK 21 honours it, JDK 24+ ignores it and is
rem generational anyway. Verified against 21.0.9 and 26.0.1.
set "JAVA=java"
if exist "%~dp0jre\bin\java.exe" set "JAVA=%~dp0jre\bin\java.exe"
"%JAVA%" -XX:+IgnoreUnrecognizedVMOptions -XX:+UseZGC -XX:+ZGenerational -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=false -jar hafen.jar
