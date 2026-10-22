/* A real ELF named "bash" that simply becomes /system/bin/sh.
 *
 * The obvious shim -- a two-line shell script at files/bin/bash -- does not work: Android 10
 * and later refuse execve on files under the app's data directory, so spawning it fails with
 * EACCES and nothing runs:
 *
 *   spawn bash EACCES
 *
 * Files shipped inside the APK and extracted into nativeLibraryDir are exempt (that is how
 * dsh runs its bundled node and ripgrep), so bash lives here as libbashsh.so and is symlinked
 * into files/bin/bash. execve follows the symlink and checks the resolved inode, which is
 * executable.
 *
 * argv is forwarded unchanged so that `bash -c "...">, `bash script.sh` and an interactive
 * `bash` all behave the way a caller expects; only argv[0] is rewritten to "sh" so that $0
 * and error messages do not claim to be bash when this is toybox.
 */
#include <unistd.h>
#include <string.h>

int main (int argc, char **argv)
{
  argv[0] = (char *) "sh";
  execv ("/system/bin/sh", argv);
  /* Only reached if execv failed. */
  static const char msg[] = "bashsh: could not exec /system/bin/sh\n";
  write (2, msg, sizeof (msg) - 1);
  return 127;
}
