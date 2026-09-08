#!/bin/bash
# Isolated Navigation Lab client. Binds DevControl on 127.0.0.1:18762 so it
# does not collide with a normal graphical session on 18761.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${HAVEN_DEV_CONTROL_PORT:-18762}"
STAGE="${PF_TEST_RUNTIME:-$ROOT/play/pf-test-runtime}"

if [[ ! -f "$ROOT/bin/hafen.jar" ]]; then
    echo "missing $ROOT/bin/hafen.jar (run: ant bin)" >&2
    exit 1
fi

mkdir -p "$STAGE/bin"
# Stage a private runtime so this client cannot clobber the interactive session.
cp -a "$ROOT/bin/." "$STAGE/bin/"
cd "$STAGE/bin"

echo "pf-test: staged $STAGE, dev control http://127.0.0.1:${PORT}/"

exec java \
    -Dsun.java2d.uiScale.enabled=false \
    -Dsun.java2d.win.uiScaleX=1.0 \
    -Dsun.java2d.win.uiScaleY=1.0 \
    -Xss8m -Xms1024m -Xmx4096m \
    --add-exports java.base/java.lang=ALL-UNNAMED \
    --add-exports java.desktop/sun.awt=ALL-UNNAMED \
    --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
    -Dhaven.dev.control.port="$PORT" \
    ${HAVEN_AUTOLOGIN:+-Dhaven.autologin="$HAVEN_AUTOLOGIN"} \
    ${HAVEN_AUTOPLAY:+-Dhaven.autoplay="$HAVEN_AUTOPLAY"} \
    -jar hafen.jar "$@"
