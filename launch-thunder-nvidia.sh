#!/bin/sh
# Regular Thunder (HUD / fishing / curio / minesweeper). Niri-patched jar.

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA=/usr/lib/jvm/java-21-openjdk/bin/java
CRASH_DIR="$ROOT/bin/dev-snapshots/crashes"
JAR="$ROOT/bin/hafen-niri.jar"

if [ ! -x "$JAVA" ]; then
    JAVA=java
fi

if [ ! -f "$JAR" ]; then
    echo "missing $JAR (in $ROOT run: ant niri-bin)" >&2
    exit 1
fi

export __NV_PRIME_RENDER_OFFLOAD=1
export __GLX_VENDOR_LIBRARY_NAME=nvidia

mkdir -p "$CRASH_DIR" || exit 1

cd "$ROOT/bin" || exit 1

exec "$JAVA" \
    -XX:ErrorFile="$CRASH_DIR/hs_err_pid%p.log" \
    -XX:HeapDumpPath="$CRASH_DIR" \
    -Dhaven.awt.clientframe=true \
    -Dsun.java2d.uiScale.enabled=false \
    -Xss8m \
    -Xms1024m \
    -Xmx4096m \
    --add-exports java.base/java.lang=ALL-UNNAMED \
    --add-exports java.desktop/sun.awt=ALL-UNNAMED \
    --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
    -DrunningThroughSteam=false \
    -Dhaven.dev.control.port="${HAVEN_DEV_CONTROL_PORT:-18761}" \
    -Dhaven.autologin="${HAVEN_AUTOLOGIN:-false}" \
    -Dhaven.autoplay="${HAVEN_AUTOPLAY:-}" \
    -jar hafen-niri.jar "$@"
