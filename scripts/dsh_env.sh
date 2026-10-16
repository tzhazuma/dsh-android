#!/data/data/com.termux/files/usr/bin/bash
# Runs INSIDE Termux (typed as: bash /sdcard/Download/dsh_env.sh)
OUT=/sdcard/Download/dsh_env.txt
{
echo "=== date ==="; date
echo "=== uname ==="; uname -a
echo "=== termux prefix ==="; echo "PREFIX=$PREFIX HOME=$HOME"
echo "=== node/npm ==="; command -v node; command -v npm; command -v npx
node -v 2>&1
npm -v 2>&1
echo "=== node process.platform/arch ==="
node -e 'console.log("platform="+process.platform, "arch="+process.arch, "libc="+process.report&&"n/a")' 2>&1
node -p 'JSON.stringify({platform:process.platform,arch:process.arch,versions:process.versions})' 2>&1
echo "=== python/gcc ==="; command -v python3; python3 -V 2>&1; command -v gcc; gcc --version 2>&1 | head -1; command -v make; command -v cmake; command -v git
echo "=== df ==="; df -h $PREFIX 2>&1 | tail -2
echo "=== installed relevant pkgs ==="; dpkg -l 2>/dev/null | grep -E 'nodejs|npm|clang|make|python|ripgrep' | head -20
echo "=== npm cache dir ==="; npm config get cache 2>&1
echo "=== END ==="
} > $OUT 2>&1
echo "WROTE $OUT rc=$?"
