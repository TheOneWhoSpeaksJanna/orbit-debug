#!/usr/bin/env bash
# On-emulator smoke test for one flavor APK.
# Usage: smoke-test.sh <flavor>
set -e
FLAVOR="$1"
APK=$(ls apk/*.apk | head -1)
if [ "$FLAVOR" = "normal" ]; then PKG="Orbit.app"; else PKG="Orbit.$FLAVOR"; fi
echo "APK=$APK  PKG=$PKG"
adb install -r "$APK"
adb logcat -c
# Launch the app (no key needed for a boot/runtime smoke test).
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 12
echo "=== logcat (Orbit / runtime / agent) ==="
adb logcat -d -s "Orbit AI" "TermuxRuntime" "HermesRuntime" "ChatViewModel" \
  "OpenRouterProvider" "ProviderCatalog" 2>/dev/null | tail -30
echo "=== crash check ==="
CRASH=$(adb logcat -d 2>/dev/null | grep -c "FATAL EXCEPTION\|AndroidRuntime: Crash" || true)
echo "fatal-crash-lines=$CRASH"
if [ "$CRASH" -gt 0 ]; then
  echo "APP CRASHED on $FLAVOR"
  adb logcat -d | grep -A 25 "FATAL EXCEPTION" | head -60
  exit 1
fi
echo "$FLAVOR: launched, no fatal crash"
adb uninstall "$PKG" >/dev/null 2>&1 || true
