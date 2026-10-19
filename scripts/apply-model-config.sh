#!/usr/bin/env bash
# Copy the Mac's dsh model configuration into the Android app's private data dir.
#
# WHY NOT IN THE APK: the release APK is a public GitHub asset. Baking
# DEEPSEEK_API_KEY (or the account token) into assets/ would publish it. The
# keys stay in the app's private storage (mode 600, app uid); this script
# re-applies them after a fresh install.
#
# Usage: scripts/apply-model-config.sh [adb-serial]
#   default serial: 192.168.0.108:5555
set -euo pipefail
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
SERIAL="${1:-192.168.0.108:5555}"
ADB=(adb -s "$SERIAL")
PKG=com.dsh.android
FILES=/data/user/0/$PKG/files

[ -f "$HOME/.dsh/.credentials.yaml" ] || { echo "no ~/.dsh/.credentials.yaml"; exit 1; }
[ -f "$HOME/.dsh/profiles/desktop/cordis.patch.yml" ] || { echo "no desktop profile patch"; exit 1; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

# device's own browser session must be preserved (it is this app's login)
"${ADB[@]}" shell "run-as $PKG cat files/.dsh/.credentials.yaml" > "$tmp/device.yaml" 2>/dev/null || true
cp "$HOME/.dsh/.credentials.yaml" "$tmp/mac.yaml"

/usr/bin/python3 - "$tmp" <<'PY'
import sys, os, yaml
d = sys.argv[1]
mac = yaml.safe_load(open(os.path.join(d, 'mac.yaml'))) or {}
try:
    dev = yaml.safe_load(open(os.path.join(d, 'device.yaml'))) or {}
except Exception:
    dev = {}
out = {'version': mac.get('version', 1),
       'refs': dict(mac.get('refs') or {}),
       'records': dict(mac.get('records') or {})}
# the device's browser-session wins; drop other clients' sessions
out['records'].pop('client-connection/browser-session', None)
for k, v in (dev.get('records') or {}).items():
    out['records'][k] = v
with open(os.path.join(d, 'merged.yaml'), 'w') as f:
    yaml.safe_dump(out, f, sort_keys=False, allow_unicode=True)
os.chmod(os.path.join(d, 'merged.yaml'), 0o600)
print('refs:', ', '.join(out['refs']))
print('records:', ', '.join(out['records']))
PY

"${ADB[@]}" shell "run-as $PKG mkdir -p files/.dsh/profiles/web"
"${ADB[@]}" shell "run-as $PKG sh -c 'cat > $FILES/.dsh/.credentials.yaml'" < "$tmp/merged.yaml"
"${ADB[@]}" shell "run-as $PKG sh -c 'cat > $FILES/.dsh/profiles/web/cordis.patch.yml'" \
    < "$HOME/.dsh/profiles/desktop/cordis.patch.yml"
echo "installed credentials + profile patch; restarting the app"
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
echo "done. NOTE: never commit the generated files; they contain live keys."
