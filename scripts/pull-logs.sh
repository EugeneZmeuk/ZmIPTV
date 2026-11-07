#!/bin/sh
# Pull the ZmIPTV journal from the TV (or emulator) to ./logs/<date>/.
#   scripts/pull-logs.sh <TV-IP>:43349         # Bravia: IP and port from "Wireless debugging" (the port changes)
#   scripts/pull-logs.sh <TV-IP>               # TVs with classic network adb on port 5555
#   scripts/pull-logs.sh                       # already connected device / emulator
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
cd "$(dirname "$0")/.." || exit 1
if [ -n "$1" ]; then
  case "$1" in *:*) TARGET="$1" ;; *) TARGET="$1:5555" ;; esac
  "$ADB" connect "$TARGET" || exit 1
  export ANDROID_SERIAL="$TARGET"
fi
OUT="logs/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
DIR=/sdcard/Android/data/com.zmeuk.zmiptv/files/logs
if ! "$ADB" pull "$DIR/." "$OUT/" >/dev/null 2>&1; then
  # fallback for debug builds when shared storage is not reachable
  "$ADB" exec-out run-as com.zmeuk.zmiptv sh -c "cat $DIR/zmiptv.log 2>/dev/null || cat files/logs/zmiptv.log" > "$OUT/zmiptv.log"
fi
# system log since the last reboot (ring buffer): crashes, ExoPlayer and codec messages
"$ADB" logcat -d -v time > "$OUT/logcat.txt"
"$ADB" logcat -d -b crash -v time > "$OUT/crash.txt" 2>/dev/null
ls -la "$OUT"
echo "Saved to $(pwd)/$OUT"
