#!/usr/bin/env bash
# Build Remote Daemon (the Go daemon and its Android starter), put the daemon and a config on the TV, and start it.
#   TV_HOST=<tv ip> addons/remote-daemon/install.sh <config> [mode]
# mode is passed as -mode: watch (the default; read the remote, bound keys also keep their own function) or full
# (take the remote over; needs a TV that accepts a uinput copy of it, which the Prosonic does not).
set -euo pipefail
CONFIG="${1:?usage: install.sh <config> [mode]}"
MODE="${2:-watch}"
[ -f "$CONFIG" ] || { echo "no such file: $CONFIG" >&2; exit 1; }
CONFIG="$(cd "$(dirname "$CONFIG")" && pwd)/$(basename "$CONFIG")"
if command -v cygpath >/dev/null 2>&1; then CONFIG="$(cygpath -m "$CONFIG")"; fi
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=io.github.rasmuskd.remotedaemon
# Script paths stay relative: with MSYS_NO_PATHCONV, Git Bash would hand Windows Python a /c/... path.
tv() { "$PY" -I ../../tools/tv.py "$@"; }

(cd daemon && go test ./... >/dev/null && GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0 \
  go build -trimpath -ldflags="-s -w" -o build/remote-daemon .)
[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=${ANDROID_HOME//\\//}" > local.properties; } || true
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew assembleRelease -q --console=plain

# A running daemon keeps the old binary open: stop it first ("pkill -x", so the match is the process name and not
# this shell's own command line), then replace and start.
tv 'pkill -x remote-daemon; true' >/dev/null
tv --push daemon/build/remote-daemon /data/local/tmp/remote-daemon >/dev/null
tv --push "$CONFIG" /data/local/tmp/remote-daemon.conf >/dev/null
# The config names the commands the daemon runs as the shell user: only that user may read or change any of it.
tv "cd /data/local/tmp && echo '-mode $MODE' > remote-daemon.args && touch remote-daemon.log && \
chmod 700 remote-daemon && chmod 600 remote-daemon.conf remote-daemon.args remote-daemon.log && sync"

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/remote-daemon.apk
# The copy stays: the daemon loads its Inject helper from it, not from whatever package holds the starter's name.
OUT=$(tv 'chmod 600 /data/local/tmp/remote-daemon.apk && pm install -r /data/local/tmp/remote-daemon.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

# The starter starts the daemon through the TV's own ADB port; the first time, the TV asks to allow its key.
tv "am start -n $PKG/.StartActivity >/dev/null"
echo "On the TV: allow debugging for remote-daemon@tv and tick Always allow (first install only)."
for _ in $(seq 1 40); do
  if tv 'pidof remote-daemon >/dev/null && echo up' | grep -q up; then
    tv 'tail -1 /data/local/tmp/remote-daemon.log'
    echo "Installed and running. It starts again after every reboot."
    exit 0
  fi
  sleep 3
done
echo "ERROR: the daemon did not start; see 'logcat -s RemoteDaemon' and /data/local/tmp/remote-daemon.log" >&2
exit 1
