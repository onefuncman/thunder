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

rem Register the client's JVM for the high-performance GPU (the per-app
rem profile from Settings ^> System ^> Display ^> Graphics). Keyed on the
rem exact java.exe that runs the client. Written only when no entry exists
rem yet, so a choice made in the Settings UI is never overridden. Matters
rem on hybrid-GPU laptops, which otherwise put the client on the iGPU.
set "JAVAPATH="
if exist "jre\bin\java.exe" (
  set "JAVAPATH=%~dp0jre\bin\java.exe"
) else (
  for %%i in (java.exe) do if not defined JAVAPATH set "JAVAPATH=%%~$PATH:i"
)
if defined JAVAPATH (
  reg query "HKCU\Software\Microsoft\DirectX\UserGpuPreferences" /v "%JAVAPATH%" >nul 2>&1
  if errorlevel 1 reg add "HKCU\Software\Microsoft\DirectX\UserGpuPreferences" /v "%JAVAPATH%" /t REG_SZ /d "GpuPreference=2;" >nul 2>&1
)

"%JAVA%" -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=false -jar hafen.jar
if errorlevel 1 pause
