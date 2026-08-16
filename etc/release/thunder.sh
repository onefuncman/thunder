#!/bin/sh
# Thunder client launcher for Linux/macOS. Requires Java 17 or newer on PATH.
cd "$(dirname "$0")" || exit 1
exec java \
  -Dsun.java2d.uiScale.enabled=false \
  -Xss8m \
  -Xms1024m \
  -Xmx4096m \
  --add-exports java.base/java.lang=ALL-UNNAMED \
  --add-exports java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
  -DrunningThroughSteam=false \
  -jar hafen.jar "$@"
