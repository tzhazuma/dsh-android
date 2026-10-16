#!/bin/bash
# Build the minimal dsh bridge APK with the raw SDK tools (no Gradle).
# Purpose: verify the DOCUMENTED Termux dispatch channel
#   our APK (holds com.termux.permission.RUN_COMMAND)
#     -> startService(com.termux/.app.RunCommandService)
# with no UI injection and no device-focus race.
set -euo pipefail

SDK=$HOME/Library/Android/sdk
BT=$SDK/build-tools/35.0.1
PLATFORM=$SDK/platforms/android-35/android.jar
ROOT=$HOME/dsh-android/bridge
OUT=$ROOT/out
KS=$ROOT/debug.keystore

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res"

echo "=== [1/6] javac ==="
javac --release 11 -classpath "$PLATFORM" -d "$OUT/classes" \
  "$ROOT"/src/com/dsh/bridge/*.java 2>&1 | grep -vE 'bootstrap class path|^Note:' || true
find "$OUT/classes" -name '*.class'

echo "=== [2/6] d8 (dex) ==="
"$BT/d8" --min-api 24 --lib "$PLATFORM" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')
ls -la "$OUT/dex"

echo "=== [3/6] aapt2 link (resources+manifest) ==="
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 35 \
  --version-code 1 --version-name 1.0

echo "=== [4/6] inject classes.dex ==="
cd "$OUT/dex" && zip -q -u "$OUT/base.apk" classes.dex

echo "=== [5/6] zipalign ==="
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "=== [6/6] sign ==="
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias dshbridge -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=dsh bridge, O=dsh-android" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias dshbridge --out "$OUT/dsh-bridge.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/dsh-bridge.apk" | head -5
ls -la "$OUT/dsh-bridge.apk"
echo "=== BUILD OK ==="
