@echo off
rem Run from this file's folder, so the client can be started from anywhere.
cd /d "%~dp0"
rem Use the bundled Java runtime if present, otherwise fall back to system Java.
set "JAVA=java"
if exist "%~dp0jre\bin\java.exe" set "JAVA=%~dp0jre\bin\java.exe"
"%JAVA%" -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=false -jar hafen.jar
REM Generational ZGC alternative (opt-in, not default): uncomment to try
REM java -XX:+UseZGC -XX:+ZGenerational -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=false -jar hafen.jar
