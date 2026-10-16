#!/bin/bash
# dsh-android: push a local script into Termux's PRIVATE dir and run it as the
# Termux uid via `adb shell run-as com.termux`.
#
# WHY THIS EXISTS: adb shell cannot drive Termux's RunCommandService (needs a
# dangerous permission the shell uid lacks), and UI injection (input text) loses
# races with other agents holding device focus. `run-as com.termux` gives us the
# Termux uid directly: no root, no focus, no injection.
#
# CRITICAL: never pass a multi-word command through `adb shell` — the device shell
# re-parses it and `-c` swallows only the first word. Always push a FILE, then
# invoke it as a single argv.
#
# usage:
#   tx.sh push <local-script> [remote-name]      # push into ~/dshx/
#   tx.sh run  <remote-name> [--login]           # run it, print stdout+stderr
#   tx.sh sh   '<one-liner>'                     # run a one-liner (written to a file)
set -u
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
DEV="${DSH_DEV:-192.168.0.108:44939}"
TROOT=/data/data/com.termux/files
THOME=$TROOT/home
RUNDIR=$THOME/dshx
BASH_BIN=$TROOT/usr/bin/bash
R="adb -s $DEV"

ensure_dev() {
  if ! $R get-state >/dev/null 2>&1; then
    echo "[tx] device $DEV not ready, reconnecting..." >&2
    $R connect "$DEV" >/dev/null 2>&1
    sleep 1
  fi
  $R shell true >/dev/null 2>&1 || { echo "[tx] DEVICE OFFLINE: $DEV" >&2; exit 9; }
}

push() {
  local src="$1" name="${2:-$(basename "$1")}"
  ensure_dev
  $R shell run-as com.termux mkdir -p "$RUNDIR" >/dev/null 2>&1
  # tee via stdin: avoids /sdcard, avoids quoting, binary-safe enough for text
  $R shell run-as com.termux tee "$RUNDIR/$name" < "$src" >/dev/null || return 1
  $R shell run-as com.termux chmod 755 "$RUNDIR/$name" >/dev/null 2>&1
  echo "[tx] pushed $RUNDIR/$name"
}

run() {
  local name="$1"; shift
  ensure_dev
  # single argv = the script path; all env setup lives INSIDE the script
  $R shell run-as com.termux "$BASH_BIN" "$RUNDIR/$name" "$@"
}

oneliner() {
  local tmp; tmp=$(mktemp)
  # NOTE: each export MUST be its own line. `export PREFIX=/x PATH=$PREFIX/bin:$PATH`
  # expands $PREFIX BEFORE the assignment lands => empty PREFIX, no Termux bin dir
  # on PATH => "node: command not found". Same family as predecessor trap #3.
  { echo "#!/data/data/com.termux/files/usr/bin/bash"
    echo 'source /etc/profile >/dev/null 2>&1'
    echo 'export PREFIX=/data/data/com.termux/files/usr'
    echo 'export HOME=/data/data/com.termux/files/home'
    echo 'export PATH=$PREFIX/bin:$PATH'
    echo 'export LD_LIBRARY_PATH=$PREFIX/lib'
    echo 'export TMPDIR=$PREFIX/tmp'
    echo "$1"; } > "$tmp"
  push "$tmp" _oneline.sh >/dev/null && run _oneline.sh
  rm -f "$tmp"
}

case "${1:-}" in
  push) shift; push "$@" ;;
  run)  shift; run "$@" ;;
  sh)   shift; oneliner "$1" ;;
  *) echo "usage: $0 {push|run|sh} ..." >&2; exit 2 ;;
esac
