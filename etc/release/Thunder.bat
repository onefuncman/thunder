@echo off
cd /d "%~dp0"
set JAVA=java
if exist "jre\bin\java.exe" set JAVA=jre\bin\java.exe

rem Finish an update staged on a previous run (a running jar/bat cannot
rem replace itself, so the updater leaves *.new files for us to swap in).
if exist "updater.jar.new" move /y "updater.jar.new" "updater.jar" >nul

rem Check GitHub releases and self-update. Fails open: if the check or the
rem download fails, the current version launches. Set THUNDER_NO_UPDATE=1
rem to skip the check entirely.
if exist "updater.jar" "%JAVA%" -jar updater.jar

rem If the updater staged a new launcher, swap it in and restart with it.
if exist "%~nx0.new" (
  move /y "%~nx0.new" "%~nx0" >nul
  start "" "%~f0"
  exit /b
)

"%JAVA%" -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=false -jar hafen.jar
if errorlevel 1 pause
