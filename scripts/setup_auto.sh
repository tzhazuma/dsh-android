#!/data/data/com.termux/files/usr/bin/bash
# One-time setup INSIDE Termux: install an auto-run hook into ~/.bashrc
# so long-running jobs do not depend on adb UI-injection focus (which is
# unreliable while another agent drives the same device).
set -u
LOG=/sdcard/Download/dsh_setup.txt
{
  echo "=== setup start $(date) ==="
  echo "HOME=$HOME"
  # 1) a generic trigger file: /sdcard/Download/dsh_auto.sh
  #    whenever a NEW Termux shell starts, if that file exists it is run
  #    in the background exactly once (it renames itself immediately).
  HOOK='\n# --- dsh-android auto hook (added by setup_auto.sh) ---\nif [ -f /sdcard/Download/dsh_auto.sh ]; then\n  bash /sdcard/Download/dsh_auto.sh >/dev/null 2>&1 &\nfi\n'
  if grep -q 'dsh-android auto hook' "$HOME/.bashrc" 2>/dev/null; then
    echo "hook already present"
  else
    printf "$HOOK" >> "$HOME/.bashrc"
    echo "hook appended, rc=$?"
  fi
  echo "--- tail of .bashrc ---"
  tail -8 "$HOME/.bashrc"
  # 2) work dirs
  mkdir -p "$HOME/dsh-test" "$HOME/.dsh-run"
  echo "mkdir rc=$?"
  # 3) marker to prove the hook works
  rm -f /sdcard/Download/dsh_hook_marker.txt
  cat > /sdcard/Download/dsh_auto.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
mv /sdcard/Download/dsh_auto.sh /sdcard/Download/dsh_auto.done 2>/dev/null
echo "HOOK_FIRED $(date)" > /sdcard/Download/dsh_hook_marker.txt
EOF
  chmod 755 /sdcard/Download/dsh_auto.sh
  echo "trigger planted at $(date)"
  echo "=== setup end ==="
} > $LOG 2>&1
echo "SETUP_DONE"
