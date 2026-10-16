#!/data/data/com.termux/files/usr/bin/bash
OUT=/sdcard/Download/dsh_probe2.txt
{
echo "=== node headers for node-gyp ==="
ls $PREFIX/include/node/node.h 2>&1
ls $PREFIX/include/node/common.gypi 2>&1 | head -2
ls $PREFIX/include/node/config.gypi 2>&1 | head -2
echo "=== npm/node-gyp ==="
command -v node-gyp; node-gyp --version 2>&1 | head -2
npm ls -g --depth=0 2>&1 | head -10
echo "=== python for gyp ==="; command -v python3; python3 -c 'print(1+1)' 2>&1
echo "=== termux.properties allow-external-apps ==="
cat $HOME/.termux/termux.properties 2>&1 | grep -v '^#' | grep -v '^$'
echo "=== writable /sdcard from termux ==="
echo termux_sdw > /sdcard/Download/dsh_sdw.txt 2>&1; cat /sdcard/Download/dsh_sdw.txt
echo "=== network reach npm registry ==="
timeout 20 curl -sS -o /dev/null -w 'npm_registry_http=%{http_code}\n' https://registry.npmjs.org/@deepseek-ai%2fdsh 2>&1
echo "=== END ==="
} > $OUT 2>&1
echo "WROTE rc=$?"
