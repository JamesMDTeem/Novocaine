@echo off
rem ---------------------------------------------------------------------------
rem THIS FILE IS THE JVM FLAG LIST. It is not the launcher any more - run
rem Novocaine.bat for that (it updates or builds first, and starts the game with
rem no console attached). Play.bat survives for two readers that need the flags
rem as a literal command line:
rem
rem   * the Steam HL launcher, which hafen.hl points at with `command-file
rem     Play.bat` and parses for the --add-exports and -D properties;
rem   * Novocaine.ps1, which reads the line below rather than writing the flags
rem     out a second time.
rem
rem So: edit the flags HERE, once, and both readers agree. Running this file
rem directly still works and gives you a console-attached client on G1.
rem ---------------------------------------------------------------------------
cd /d "%~dp0"
rem Use the bundled Java runtime if present, otherwise fall back to system Java.
set "JAVA=java"
if exist "%~dp0jre\bin\java.exe" set "JAVA=%~dp0jre\bin\java.exe"
rem -XX:+UseCompactObjectHeaders shrinks the object header from 12 bytes to 8 and is a
rem product feature from JDK 25. Measured here at 28.61 -> 20.58 bytes for a two-int
rem object, though 8-byte alignment absorbs it for some layouts. It does not exist before
rem 25 and is FATAL there ("Unrecognized VM option"), so IgnoreUnrecognizedVMOptions is
rem load-bearing: it keeps this launcher working on a system JDK 21 with no bundled jre.
rem
rem Generational ZGC instead of G1 is `Novocaine.bat -ZGC`; see Novocaine.ps1 for the
rem pause-time and footprint measurements behind that trade.
"%JAVA%" -XX:+IgnoreUnrecognizedVMOptions -XX:+UseCompactObjectHeaders -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=false -jar hafen.jar
