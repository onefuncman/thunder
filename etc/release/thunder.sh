#!/bin/sh
# Thunder client launcher for Linux/macOS. Requires Java 17 or newer on PATH.
cd "$(dirname "$0")" || exit 1

# Finish an update staged on a previous run (the updater cannot replace its
# own running jar or this script, so it leaves *.new files for us to swap in).
[ -f updater.jar.new ] && mv -f updater.jar.new updater.jar

# Check GitHub releases and self-update. Fails open: if the check or the
# download fails, the current version launches. Set THUNDER_NO_UPDATE=1 to
# skip the check entirely.
[ -f updater.jar ] && java -jar updater.jar

# If the updater staged a new launcher, swap it in and re-exec it.
if [ -f thunder.sh.new ]; then
    mv -f thunder.sh.new thunder.sh
    chmod +x thunder.sh
    exec sh thunder.sh "$@"
fi

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
