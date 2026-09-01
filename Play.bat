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
rem directly still works and gives you a console-attached client on ZGC
rem (G1 via Novocaine.ps1 -NoZGC / Novocaine.bat -NoZGC).
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
rem ZGC is the default (opt out with Novocaine.bat -NoZGC / -G1 or
rem Novocaine.ps1 -NoZGC); see Novocaine.ps1 for trade (ZGC peaks 3632M vs
rem G1 1515M — budget accordingly when running Count > 1).
rem Heap floor 4096m here (static fallback, line 28); Novocaine.ps1 scales -Xmx to
rem 6144m (>=16G) / 8192m (>=24G or headroom for Count*HEAP+4G OS) at launch.
"%JAVA%" -XX:+IgnoreUnrecognizedVMOptions -XX:+UseZGC -XX:+ZGenerational -XX:+UseCompactObjectHeaders -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=false -jar hafen.jar
