@echo off
rem Run from this file's folder, so the client can be started from anywhere.
cd /d "%~dp0"
rem Use the bundled Java runtime if present, otherwise fall back to system Java.
rem javaw.exe is the windowless launcher: no console window is attached to the
rem client, so closing the console does not kill the game.
set "JAVA=javaw"
if exist "%~dp0jre\bin\javaw.exe" set "JAVA=%~dp0jre\bin\javaw.exe"
start "Novocaine" rem -XX:+UseCompactObjectHeaders shrinks the object header from 12 bytes to 8 and is a
rem product feature from JDK 25. Measured here at 28.61 -> 20.58 bytes for a two-int
rem object, though 8-byte alignment absorbs it for some layouts. It does not exist before
rem 25 and is FATAL there ("Unrecognized VM option"), so IgnoreUnrecognizedVMOptions is
rem load-bearing: it keeps this launcher working on a system JDK 21 with no bundled jre.
"%JAVA%" -XX:+IgnoreUnrecognizedVMOptions -XX:+UseCompactObjectHeaders -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=true -jar hafen.jar
