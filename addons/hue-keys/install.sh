#!/usr/bin/env bash
# Build Hue Keys, install it on the TV and, optionally, push its Hue bridge address, app key and room.
#   TV_HOST=<tv ip> addons/hue-keys/install.sh [config.json]   # {"bridge": "192.168.1.19", "key": "...", "room": "1"}
set -euo pipefail
CONFIG="${1:-}"
if [ -n "$CONFIG" ]; then
  [ -f "$CONFIG" ] || { echo "no such file: $CONFIG" >&2; exit 1; }
  CONFIG="$(cd "$(dirname "$CONFIG")" && pwd)/$(basename "$CONFIG")"
  if command -v cygpath >/dev/null 2>&1; then CONFIG="$(cygpath -m "$CONFIG")"; fi
fi
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=io.github.rasmuskd.huekeys
# Script paths stay relative: with MSYS_NO_PATHCONV, Git Bash would hand Windows Python a /c/... path.
tv() { "$PY" -I ../../tools/tv.py "$@"; }

[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=${ANDROID_HOME//\\//}" > local.properties; } || true
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew assembleRelease -q --console=plain

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/hue-keys.apk
OUT=$(tv 'pm install -r /data/local/tmp/hue-keys.apk; rm -f /data/local/tmp/hue-keys.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

# The bridge and its app key go in as a file through the app's provider (only the ADB shell may write it), so the
# key never sits on a command line on the TV. The app reads it once and deletes it.
if [ -n "$CONFIG" ]; then
  STAGE=/data/local/tmp/hue-keys-config.json
  tv --push "$CONFIG" "$STAGE" >/dev/null
  tv "content write --uri content://$PKG.config/config.json < $STAGE; rm -f $STAGE; echo config pushed"
fi
echo "Installed. Map remote buttons to io.github.rasmuskd.huekeys/.Toggle, .Brighter, .Dimmer or .Movie in app_keys."
