@rem Register the PATH java.exe for the high-performance GPU (the per-app
@rem profile from Settings ^> System ^> Display ^> Graphics). Only written
@rem when no entry exists yet, so a choice made in the Settings UI is never
@rem overridden. Matters on hybrid-GPU laptops, which otherwise put the
@rem client on the iGPU.
@set "JAVAPATH="
@for %%i in (java.exe) do @if not defined JAVAPATH set "JAVAPATH=%%~$PATH:i"
@if defined JAVAPATH (
  reg query "HKCU\Software\Microsoft\DirectX\UserGpuPreferences" /v "%JAVAPATH%" >nul 2>&1
  if errorlevel 1 reg add "HKCU\Software\Microsoft\DirectX\UserGpuPreferences" /v "%JAVAPATH%" /t REG_SZ /d "GpuPreference=2;" >nul 2>&1
)

java -Dsun.java2d.uiScale.enabled=false -Dsun.java2d.win.uiScaleX=1.0 -Dsun.java2d.win.uiScaleY=1.0 -Xss8m -Xms1024m -Xmx4096m --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -DrunningThroughSteam=false -jar hafen.jar

pause
