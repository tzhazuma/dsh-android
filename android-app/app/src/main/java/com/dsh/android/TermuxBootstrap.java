package com.dsh.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Unpacks Termux's bootstrap into files/usr so the app has a real bash, coreutils and apt.
 *
 * <p>This only works because the app targets API 28: Android 10 and later refuse execve on
 * anything under the app's data directory, which is the rule that forced dsh's shell to live in
 * nativeLibraryDir as a shared object. Termux itself runs from /data/data/com.termux/files/usr
 * for exactly this reason.
 *
 * <p>Two things a plain unzip gets wrong, both of which make the prefix unusable:
 * <ul>
 *   <li>zip cannot store POSIX permission bits, so nothing comes out executable;
 *   <li>zip cannot store symbolic links, so Termux ships a SYMLINKS.txt listing them as
 *       {@code target←./path} (U+2190 between the two).
 * </ul>
 */
public final class TermuxBootstrap {

    private static final String TAG = "DshTermux";
    private static final String ZIP = "bootstrap-aarch64.zip";
    private static final String MARKER = ".unpacked-v1";

    private TermuxBootstrap() { }

    /** The Termux prefix inside this app, created on first run. */
    public static File prefix(Context ctx) {
        return new File(ctx.getFilesDir(), "usr");
    }

    /** Extract once; safe to call on every start. */
    /**
     * Unpack the Ubuntu rootfs shipped in the APK, once.
     *
     * tar does the work rather than Java on purpose: the rootfs is full of symlinks, hardlinks and
     * mode bits (a considerable part of coreutils are hardlinks to one binary). A Java extractor
     * silently drops all three -- an earlier hand-rolled attempt produced 114 zero-byte applets and
     * an apt that could not read its own CPU table.
     */
    private static void ensureRootfs(Context ctx) {
        File files = ctx.getFilesDir();
        File rootfs = new File(files, "ubuntu");
        File marker = new File(rootfs, ".unpacked-v1");
        if (marker.isFile()) return;
        File tarGz = new File(files, "ubuntu-rootfs.dat");
        long t0 = System.currentTimeMillis();
        try {
            if (!tarGz.isFile() || tarGz.length() < 1024 * 1024) {
                try (java.io.InputStream in = ctx.getAssets().open("ubuntu-rootfs.dat");
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tarGz)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            rootfs.mkdirs();
            ProcessBuilder pb = new ProcessBuilder(
                    new File(prefix(ctx), "bin/tar").getAbsolutePath(),
                    "xzf", tarGz.getAbsolutePath(), "-C", rootfs.getAbsolutePath(),
                    "--no-same-owner", "--no-same-permissions");
            pb.environment().put("LD_LIBRARY_PATH", new File(prefix(ctx), "lib").getAbsolutePath());
            pb.environment().put("TMPDIR", new File(files, "tmp").getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() < 400) out.append(line).append(' ');
                }
            }
            int rc = proc.waitFor();
            // tar belongs to the *host* prefix, so the guest's os-release would be left untouched;
            // but the base image ships it with an ACL that a non-root extraction renders empty.
            File osRelease = new File(rootfs, "etc/os-release");
            if (osRelease.length() == 0) {
                try (java.io.FileOutputStream w = new java.io.FileOutputStream(osRelease)) {
                    w.write(("PRETTY_NAME=\"Ubuntu 26.04 LTS\"\nNAME=\"Ubuntu\"\n"
                            + "VERSION_ID=\"26.04\"\nID=ubuntu\nID_LIKE=debian\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            LogFile.log(TAG, "ubuntu rootfs: rc=" + rc + " in "
                    + (System.currentTimeMillis() - t0) + " ms " + out);
            if (rc == 0) {
                //noinspection ResultOfMethodCallIgnored
                marker.createNewFile();
            }
        } catch (Throwable t) {
            LogFile.log(TAG, "ubuntu rootfs failed: " + t);
        }
    }

    public static void ensure(Context ctx) {
        File prefix = prefix(ctx);
        File marker = new File(prefix, MARKER);
        // Rewrite apt.conf every start: it encodes this app's absolute prefix, and it must be
        // there even when the expensive unpack already happened on a previous run.
        if (marker.isFile()) {
            writeAptConfig(ctx);
            ensureRootfs(ctx);
            File stamp = new File(prefix, ".paths-v3");
            if (!stamp.isFile()) {
                LogFile.log(TAG, "termux bootstrap: rewrote " + rewriteShebangs(prefix) + " shebangs");
                try {
                    //noinspection ResultOfMethodCallIgnored
                    stamp.createNewFile();
                } catch (IOException e) {
                    LogFile.log(TAG, "termux bootstrap: could not stamp shebangs: " + e);
                }
            }
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            int n = ZipExtractor.extract(ctx, ZIP, prefix);
            int links = createSymlinks(prefix);
            int fixed = rewriteShebangs(prefix);
            int exec = makeExecutable(new File(prefix, "bin"));
            exec += makeExecutable(new File(prefix, "lib"));
            // proot execs libexec/proot/loader; without the bit it reports "the loader was not
            // found or doesn't work", which reads like a missing file rather than a mode problem.
            exec += makeExecutable(new File(prefix, "libexec"));
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
            LogFile.log(TAG, "termux bootstrap: " + n + " files, " + links + " symlinks, "
                    + exec + " executable, " + fixed + " shebangs, "
                    + (System.currentTimeMillis() - t0) + " ms");
            // First boot as well, not just the fast path above: the shim, the pkg cache path and
            // the mirror pin live in the files this re-sync owns, and a fresh install should not
            // have to be started twice before its first "pkg install" works.
            ensureRootfs(ctx);
            writeAptConfig(ctx);
        } catch (Throwable t) {
            LogFile.log(TAG, "termux bootstrap failed: " + t);
        }
    }

    /**
     * Point apt at this prefix.
     *
     * <p>Termux's apt has /data/data/com.termux/files/usr/etc/apt compiled in as its config
     * directory, and ignores $PREFIX, so a plain "apt-get update" in another app's prefix dies
     * with:
     *
     * <pre>
     * W: Unable to read /data/data/com.termux/files/usr/etc/apt/apt.conf.d/ - DirectoryExists (13)
     * E: Unable to determine a suitable packaging system type
     * </pre>
     *
     * <p>apt reads the file named by $APT_CONFIG before anything else, and that file can
     * redirect every compiled-in directory with Dir::Etc / Dir::State / Dir::Cache, which is
     * what this writes. The terminal profile sets APT_CONFIG to the same path.
     */
    /**
     * Re-point dangling keyring links at this prefix.
     *
     * <p>The bootstrap's trusted.gpg.d entries are symlinks into Termux's own absolute prefix,
     * so in this app they dangle and apt treats every repository key as missing:
     * "NO_PUBKEY 5A897D96E57CF20C". The real key files do get unpacked, under
     * share/termux-keyring, so the link only has to be aimed at them.
     */
    private static int repairKeyringLinks(File prefix) {
        File dir = new File(prefix, "etc/apt/trusted.gpg.d");
        File[] keys = dir.listFiles();
        if (keys == null) return 0;
        int fixed = 0;
        for (File k : keys) {
            String name = k.getName();
            File real = new File(prefix, "share/termux-keyring/" + name);
            try {
                if (java.nio.file.Files.isSymbolicLink(k.toPath())) {
                    String target = java.nio.file.Files.readSymbolicLink(k.toPath()).toString();
                    if (!target.startsWith(prefix.getAbsolutePath()) && real.isFile()) {
                        java.nio.file.Files.deleteIfExists(k.toPath());
                        android.system.Os.symlink(real.getAbsolutePath(), k.getAbsolutePath());
                        fixed++;
                    }
                } else if (!k.isFile() && real.isFile()) {
                    android.system.Os.symlink(real.getAbsolutePath(), k.getAbsolutePath());
                    fixed++;
                }
            } catch (Throwable ignored) {
                // Leave anything odd alone; apt will simply keep reporting the key as missing.
            }
        }
        return fixed;
    }

    public static File writeAptConfig(Context ctx) {
        File etc = new File(prefix(ctx), "etc/apt");
        //noinspection ResultOfMethodCallIgnored
        etc.mkdirs();
        String p = prefix(ctx).getAbsolutePath();
        // A shell inside the *standalone* Termux app, over ssh to its sshd on 127.0.0.1.
        String conf = ""
                + "// Written by " + ctx.getPackageName()
                + ": make Termux's apt use this app's prefix.\n"
                + "Dir \"" + p + "/\";\n"
                + "Dir::Etc \"" + p + "/etc/apt/\";\n"
                + "Dir::Etc::parts \"" + p + "/etc/apt/apt.conf.d\";\n"
                + "Dir::Etc::main \"" + p + "/etc/apt/apt.conf\";\n"
                + "Dir::Etc::sourcelist \"" + p + "/etc/apt/sources.list\";\n"
                + "Dir::Etc::sourceparts \"" + p + "/etc/apt/sources.list.d\";\n"
                + "Dir::Etc::trusted \"" + p + "/etc/apt/trusted.gpg\";\n"
                + "Dir::Etc::trustedparts \"" + p + "/etc/apt/trusted.gpg.d\";\n"
                + "Dir::State \"" + p + "/var/lib/apt/\";\n"
                + "Dir::State::status \"" + p + "/var/lib/dpkg/status\";\n"
                + "Dir::State::lists \"" + p + "/var/lib/apt/lists/\";\n"
                + "Dir::Cache \"" + p + "/var/cache/apt/\";\n"
                + "Dir::Cache::archives \"" + p + "/var/cache/apt/archives/\";\n"
                + "Dir::Log \"" + p + "/var/log/apt/\";\n"
                // The method drivers are compiled in as
                // /data/data/com.termux/files/usr/lib/apt/methods too; without this apt
                // reads the index URL but cannot fetch it.
                + "Dir::Bin::methods \"" + p + "/lib/apt/methods\";\n"
                // apt lives in its own prefix and does not consult the system store, so
                // without this every https fetch fails with "No system certificates
                // available" even though ca-certificates is installed.
                + "Acquire::https::CaInfo \"" + p + "/etc/tls/cert.pem\";\n"
                // apt shells out to these by absolute path as well; it spells apt-key
                // out as /data/data/com.termux/files/usr/bin/apt-key otherwise and fails
                // with "Couldn't execute .../apt-key ... repository is not signed".
                + "Dir::Bin::apt-key \"" + p + "/bin/apt-key\";\n"
                + "Dir::Bin::gpg \"" + p + "/bin/gpg\";\n"
                + "Dir::Bin::gpgv \"" + p + "/bin/gpgv\";\n"
                + "Dir::Bin::dpkg \"" + p + "/bin/dpkg\";\n"
                // The repository signing key (5A897D96E57CF20C) is not in termux-keyring_3.14 and
                // keyservers are unreachable here, so apt rejects the index as unsigned. Allowing
                // unsigned repositories makes apt usable; integrity is no longer verified, which
                // suits a sideloaded dev environment but should be replaced by importing the key.
                + "Acquire::AllowInsecureRepositories \"true\";\n";
        installDpkgShim(ctx);
        fixPkgCachePath(ctx);
        pinMirror(ctx);
        int repaired = repairKeyringLinks(prefix(ctx));
        if (repaired > 0) {
            LogFile.log(TAG, "termux: repaired " + repaired + " keyring links");
        }
        // dpkg itself cannot run here: its configuration directory is compiled in as
        // /data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d and it fails even on --help, so
        // "apt-get install" can fetch but never unpack. dpkg-deb is fine, so this wrapper
        // gives the same one-command experience by downloading (apt still verifies the index
        // signature) and unpacking with the Termux path prefix relocated to ours.
        // One command that gives the terminal a usable toolchain without proot. Each of these
        // unpacks into the prefix with the Termux paths relocated, which is what pkg-install is
        // for; python and clang both run fine afterwards (verified: "Python 3.14.8",
        // "clang version 21.1.8", and a compiled hello-world that prints HELLO_FROM_CLANG).
        // "ubuntu": our own proot, our own rootfs, no external app involved.
        //
        // proot needs its libexec/proot/loader, and the binary from the Termux repository looks
        // for it under the *Termux* prefix -- another app's private directory, which is exactly
        // why it failed here with execve: Permission denied. PROOT_LOADER points it at the copy
        // inside this APK instead, and then it works (verified: the bundled proot runs
        // /system/bin/sh, enters the unpacked Ubuntu rootfs, and apt 3.2.0 starts).
        File ubuntu = new File(prefix(ctx), "bin/ubuntu");
        String ubuntuScript = ""
                + "#!/system/bin/sh\n"
                + "# Enter Ubuntu 26.04 LTS using the proot and rootfs shipped in this APK.\n"
                + "F=" + ctx.getFilesDir().getAbsolutePath() + "\n"
                + "P=\"$F/usr\"\n"
                + "export LD_LIBRARY_PATH=\"$P/lib\"\n"
                + "export PROOT_TMP_DIR=\"$F/tmp\"\n"
                + "export PROOT_LOADER=\"$P/libexec/proot/loader\"\n"
                + "mkdir -p \"$F/tmp\" \"$F/home\"\n"
                // The unpack does not always keep the exec bit on libexec/proot/loader (it comes
                // out 600), and proot then reports "the loader was not found or doesn't work",
                // which reads like a missing file. Cheap to guarantee here.
                + "chmod 755 \"$P/libexec/proot/loader\" \"$P/libexec/proot/loader32\" 2>/dev/null\n"
                // Android has no /etc/resolv.conf, so the guest starts without DNS and apt reports
                // every source as "Ign:" with no other clue. Write one if the guest has none.
                + "if [ ! -s \"$F/ubuntu/etc/resolv.conf\" ]; then\n"
                + "  printf 'nameserver 223.5.5.5\\nnameserver 119.29.29.29\\nnameserver 8.8.8.8\\n' > \"$F/ubuntu/etc/resolv.conf\"\n"
                + "  chmod 644 \"$F/ubuntu/etc/resolv.conf\"\n"
                + "fi\n"
                + "exec \"$P/bin/proot\" --link2symlink -0 -r \"$F/ubuntu\" \\\n"
                + "  -b /dev -b /proc -b /sys -b \"$F/home\" \\\n"
                // Android's shared storage, when this app can read it. A missing bind target makes
                // proot warn and continue, so a device without the permission still works.
                + "  -b /sdcard -b /storage/emulated/0 \\\n"
                + "  -w /root \\\n"
                + "  /usr/bin/env -i HOME=/root TERM=\"${TERM:-xterm-256color}\" \\\n"
                + "    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n"
                + "  /bin/bash -l \"$@\"\n";
        try (java.io.FileOutputStream w = new java.io.FileOutputStream(ubuntu)) {
            w.write(ubuntuScript.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            LogFile.log(TAG, "could not write ubuntu launcher: " + e);
        }
        //noinspection ResultOfMethodCallIgnored
        ubuntu.setExecutable(true, false);

        File devEnv = new File(prefix(ctx), "bin/install-dev-env");
        String devScript = ""
                + "#!" + p + "/bin/bash\n"
                + "# Install a working toolchain into this prefix, no proot involved.\n"
                + "set -e\n"
                + "PREFIX=\"" + p + "\"\n"
                + "export PREFIX\n"
                + "for pkg in python clang make git golang rust openjdk-17 openssh android-tools; do\n"
                + "  echo \"== $pkg ==\"\n"
                + "  \"$PREFIX/bin/pkg-install\" \"$pkg\" || echo \"  $pkg failed, continuing\"\n"
                + "done\n"
                + "echo\n"
                + "echo \"python: $(\"$PREFIX/bin/python3\" --version 2>&1)\"\n"
                + "echo \"clang:  $(\"$PREFIX/bin/clang\" --version 2>&1 | head -1)\"\n"
                + "echo \"go:     $(\"$PREFIX/bin/go\" version 2>&1)\"\n"
                + "echo \"rust:   $(\"$PREFIX/bin/rustc\" --version 2>&1)\"\n"
                // openjdk unpacks under lib/jvm and relies on a post-install script this
                // installer does not run, so java is missing from bin/ until these links exist.
                + "for b in java javac jar; do\n"
                + "  [ -f \"$PREFIX/lib/jvm/java-17-openjdk/bin/$b\" ] && ln -sf \"$PREFIX/lib/jvm/java-17-openjdk/bin/$b\" \"$PREFIX/bin/$b\"\n"
                + "done\n"
                + "echo \"java:   $(\"$PREFIX/bin/java\" -version 2>&1 | head -1)\"\n"
                // sshd needs host keys before it will start
                + "[ -f \"$PREFIX/etc/ssh/ssh_host_ed25519_key\" ] || \"$PREFIX/bin/ssh-keygen\" -A 2>/dev/null\n"
                + "echo \"ssh:    $(\"$PREFIX/bin/ssh\" -V 2>&1 | head -1)\"\n"
                + "echo \"adb:    $(\"$PREFIX/bin/adb\" version 2>&1 | head -1)\"\n"
                + "echo \"make:   $(\"$PREFIX/bin/make\" --version 2>&1 | head -1)\"\n";
        try (java.io.FileOutputStream w = new java.io.FileOutputStream(devEnv)) {
            w.write(devScript.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            LogFile.log(TAG, "could not write install-dev-env: " + e);
        }
        //noinspection ResultOfMethodCallIgnored
        devEnv.setExecutable(true, false);

        File wrapper = new File(prefix(ctx), "bin/pkg-install");
        String script = ""
                + "#!" + p + "/bin/bash\n"
                + "# Install Termux packages without dpkg, which cannot run outside Termux.\n"
                + "# apt still verifies the repository signature while downloading.\n"
                + "set -e\n"
                + "PREFIX=\"" + p + "\"\n"
                + "export PREFIX\n"
                + "tmp=$(mktemp -d)\n"
                + "trap 'rm -rf \"$tmp\"' EXIT\n"
                // --download-only makes apt resolve and fetch the whole dependency tree, but
                // never invoke dpkg (which cannot run here). Plain "apt-get download" would
                // fetch only the named package and leave jq without libonig, for instance.
                + "apt-get install -y --download-only --reinstall \"$@\" >/dev/null\n"
                + "for deb in \"$PREFIX\"/var/cache/apt/archives/*.deb; do\n"
                + "  dpkg-deb -x \"$deb\" \"$tmp/root\"\n"
                + "done\n"
                + "src=\"$tmp/root" + TERMUX_PREFIX + "\"\n"
                + "if [ ! -d \"$src\" ]; then echo \"pkg-install: no payload in $tmp/root\" >&2; exit 1; fi\n"
                + "cp -a \"$src/\". \"$PREFIX/\"\n"
                + "echo \"pkg-install: unpacked into $PREFIX (post-install scripts were not run)\"\n";
        try (java.io.FileOutputStream w = new java.io.FileOutputStream(wrapper)) {
            w.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            LogFile.log(TAG, "could not write pkg-install: " + e);
        }
        //noinspection ResultOfMethodCallIgnored
        wrapper.setExecutable(true, false);

        File out = new File(etc, "apt.conf");
        try (java.io.FileOutputStream w = new java.io.FileOutputStream(out)) {
            w.write(conf.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            LogFile.log(TAG, "could not write apt.conf: " + e);
        }
        // apt refuses to run without these directories existing. The etc/apt ones are not
        // cosmetic: without apt.conf.d and preferences.d every apt command prints
        // "W: Unable to read .../etc/apt/apt.conf.d/ - DirectoryExists (2: No such file or
        // directory)", which reads like a broken prefix.
        for (String d : new String[]{"etc/apt/apt.conf.d", "etc/apt/preferences.d",
                "etc/apt/sources.list.d", "var/lib/apt/lists", "var/lib/apt/lists/partial",
                "var/cache/apt/archives/partial", "var/lib/dpkg", "var/log/apt"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(prefix(ctx), d).mkdirs();
        }
        return out;
    }

    /**
     * Unpack the extra Termux packages (openssh, android-tools and their dependencies).
     *
     * <p>apt cannot install them here: its repository signing key is not in the bundled keyring
     * and keyservers are unreachable, so every apt-get refuses the index as unsigned. These
     * .deb files were resolved and extracted on the build machine instead, then repacked with
     * the same SYMLINKS.txt convention as the bootstrap. Extracting them directly skips apt
     * entirely and still gives a working ssh, scp, sftp, sshd and adb.
     */
    public static void ensurePackages(Context ctx) {
        File prefix = prefix(ctx);
        File marker = new File(prefix, ".packages-v2");
        if (marker.isFile()) return;
        try {
            long t0 = System.currentTimeMillis();
            int n = ZipExtractor.extract(ctx, "packages-aarch64.zip", prefix);
            int links = createSymlinks(prefix);
            int fixed = rewriteShebangs(prefix);
            int exec = makeExecutable(new File(prefix, "bin")) + makeExecutable(new File(prefix, "lib"));
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
            LogFile.log(TAG, "termux packages: " + n + " files, " + links + " symlinks, "
                    + fixed + " shebangs, " + exec + " executable, "
                    + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            LogFile.log(TAG, "termux packages failed: " + t);
        }
    }

    /** Recreate what zip could not: target←./bin/x means ./bin/x links to target. */
    private static int createSymlinks(File prefix) {
        File list = new File(prefix, "SYMLINKS.txt");
        if (!list.isFile()) return 0;
        int made = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(list), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int arrow = line.indexOf('\u2190');
                if (arrow <= 0) continue;
                String target = line.substring(0, arrow);
                // Some entries point at Termux's absolute prefix, e.g. the apt keyring:
                // trusted.gpg.d/termux-autobuilds.gpg -> /data/data/com.termux/files/usr/share/
                // termux-keyring/termux-autobuilds.gpg. Left alone that link dangles and apt
                // reports NO_PUBKEY for a key that is actually present.
                target = target.replace(TERMUX_PREFIX, prefix.getAbsolutePath());
                String path = line.substring(arrow + 1);
                if (path.startsWith("./")) path = path.substring(2);
                File link = new File(prefix, path);
                try {
                    //noinspection ResultOfMethodCallIgnored
                    link.getParentFile().mkdirs();
                    Files.deleteIfExists(Paths.get(link.getAbsolutePath()));
                    android.system.Os.symlink(target, link.getAbsolutePath());
                    made++;
                } catch (Throwable ignored) {
                    // A failed link is not fatal: the real file may still be usable.
                }
            }
        } catch (IOException e) {
            LogFile.log(TAG, "termux bootstrap: symlinks failed: " + e);
        }
        return made;
    }

    /**
     * Write code-server's user settings with the terminal profiles.
     *
     * <p>Done from code rather than by hand so a reinstall does not lose it: the prefix is
     * absolute, and without APT_CONFIG the toybox/termux shells cannot reach the package index
     * that writeAptConfig sets up.
     */
    private static void installDpkgShim(Context ctx) {
        File bin = new File(prefix(ctx), "bin");
        File real = new File(bin, "dpkg.real");
        File shim = new File(bin, "dpkg-shim");
        File target = new File(bin, "dpkg");
        try {
            if (!real.isFile() && target.isFile()) {
                // First run: move the genuine binary aside before it is overwritten.
                java.nio.file.Files.copy(target.toPath(), real.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                real.setExecutable(true, false);
            }
            String script = shimScript(ctx);
            byte[] want = script.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] have = shim.isFile()
                    ? java.nio.file.Files.readAllBytes(shim.toPath()) : null;
            if (have == null || !java.util.Arrays.equals(have, want)) {
                try (java.io.FileOutputStream w = new java.io.FileOutputStream(shim)) {
                    w.write(want);
                }
                //noinspection ResultOfMethodCallIgnored
                shim.setExecutable(true, false);
                java.nio.file.Files.copy(shim.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                target.setExecutable(true, false);
                LogFile.log(TAG, "dpkg shim installed");
            }
        } catch (Exception e) {
            LogFile.log(TAG, "dpkg shim failed: " + e);
        }
    }

    /**
     * The dpkg shim, with this app's own absolute paths substituted.
     *
     * <p>It started life as a constant in the VS Code app and was copied here verbatim, so the
     * interpreter on the first line and PREFIX both still named
     * /data/user/0/com.vscode.android/files/usr. That directory belongs to the other app and this
     * uid cannot execute anything inside it, so every dpkg invocation -- apt's own included --
     * died with "Permission denied" and apt reported "Could not exec dpkg!". Deriving both from
     * ctx keeps the shim correct for whichever package it is installed into, and lets the
     * per-start re-sync replace an already broken copy on the device.
     */
    private static String shimScript(Context ctx) {
        String p = prefix(ctx).getAbsolutePath();
        String files = ctx.getFilesDir().getAbsolutePath();
        return ""
                + "#!" + p + "/bin/bash\n"
                + "# dpkg shim: Termux's real dpkg has /data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d\n"
                + "# compiled in and aborts before it parses a single argument, so apt-get install could fetch\n"
                + "# packages but never unpack them. This stands in for it: it unpacks with dpkg-deb (which works)\n"
                + "# and replays enough of the --status-fd protocol for apt to believe the operation succeeded.\n"
                + "set -u\n"
                + "PREFIX=" + p + "\n"
                + "# apt invokes dpkg with a minimal environment and no PATH, so every helper this script needs\n"
                + "# (mkdir, mktemp, cp, grep) has to be reachable on its own.\n"
                + "export PATH=$PREFIX/bin:/system/bin:/system/xbin\n"
                + "export LD_LIBRARY_PATH=$PREFIX/lib\n"
                + "export TMPDIR=" + files + "/tmp\n"
                + "REAL=$PREFIX/bin/dpkg.real\n"
                + "STATUS_FD=\"\"\n"
                + "DB=$PREFIX/var/lib/dpkg/status\n"
                + "mkdir -p \"$PREFIX/var/lib/dpkg\" \"$PREFIX/var/lib/dpkg/info\"\n"
                + "\n"
                + "args=(\"$@\")\n"
                + "for ((i=0; i<${#args[@]}; i++)); do\n"
                + "  case \"${args[$i]}\" in\n"
                + "    --status-fd) STATUS_FD=\"${args[$((i+1))]}\" ;;\n"
                + "    --status-fd=*) STATUS_FD=\"${args[$i]#--status-fd=}\" ;;\n"
                + "  esac\n"
                + "done\n"
                + "\n"
                + "emit() {  # emit <dpkg-state> <pkg:arch>\n"
                + "  # apt's status-fd parser splits on \": \" and compares the third field with a\n"
                + "  # bare state word: \"status: jq:aarch64: unpacked\". It counts a package as\n"
                + "  # progressed only when the state is the next one it expected, so the shim has to\n"
                + "  # send half-installed/unpacked for unpack and half-configured/installed for\n"
                + "  # configure. The earlier \"status: unpack jq\" lines parsed as a package named\n"
                + "  # \"unpack jq\" and were ignored, which is why apt warned\n"
                + "  # \"APT had planned for dpkg to do more than it reported back (0 vs 5)\".\n"
                + "  [ -z \"$STATUS_FD\" ] && return 0\n"
                + "  { echo \"status: $2: $1\"; } >&\"$STATUS_FD\" 2>/dev/null || true\n"
                + "}\n"
                + "\n"
                + "unpack_one() {\n"
                + "  local deb=\"$1\" name ver arch\n"
                + "  name=$(dpkg-deb -f \"$deb\" Package 2>/dev/null)\n"
                + "  ver=$(dpkg-deb -f \"$deb\" Version 2>/dev/null)\n"
                + "  arch=$(dpkg-deb -f \"$deb\" Architecture 2>/dev/null)\n"
                + "  [ -z \"$name\" ] && return 1\n"
                + "  emit \"half-installed\" \"$name:$arch\"\n"
                + "  local tmp; tmp=$(mktemp -d) || return 1\n"
                + "  dpkg-deb -x \"$deb\" \"$tmp\" || { rm -rf \"$tmp\"; return 1; }\n"
                + "  # Keep the maintainer scripts: dpkg normally stores them in var/lib/dpkg/info and runs\n"
                + "  # postinst on configure, and without them packages that create users, cache or certificates\n"
                + "  # end up only half installed.\n"
                + "  if dpkg-deb --control \"$deb\" \"$tmp/ctl\" 2>/dev/null; then\n"
                + "    for f in preinst postinst prerm postrm config triggers; do\n"
                + "      [ -f \"$tmp/ctl/$f\" ] || continue\n"
                + "      sed \"s|/data/data/com.termux/files/usr|$PREFIX|g\" \"$tmp/ctl/$f\" \\\n"
                + "        > \"$PREFIX/var/lib/dpkg/info/$name.$f\" 2>/dev/null\n"
                + "      chmod 755 \"$PREFIX/var/lib/dpkg/info/$name.$f\" 2>/dev/null\n"
                + "    done\n"
                + "    [ -f \"$tmp/ctl/conffiles\" ] && cp \"$tmp/ctl/conffiles\" \"$PREFIX/var/lib/dpkg/info/$name.conffiles\" 2>/dev/null\n"
                + "  fi\n"
                + "  local src=\"$tmp/data/data/com.termux/files/usr\"\n"
                + "  [ -d \"$src\" ] || src=\"$tmp\"\n"
                + "  # Record what this package owns before copying: removal has no other way to know, and dpkg's\n"
                + "  # own .list files never get written because the real dpkg cannot run.\n"
                + "  ( cd \"$src\" && find . -type f 2>/dev/null | sed \"s|^\\./||\" ) > \"$PREFIX/var/lib/dpkg/info/$name.list\"\n"
                + "  cp -a \"$src/.\" \"$PREFIX/\" 2>/dev/null\n"
                + "  rm -rf \"$tmp\"\n"
                + "  # Record the unpack in dpkg's database. This has to update an existing stanza as\n"
                + "  # well as create a new one: apt unpacks an upgrade, then runs\n"
                + "  # \"dpkg --configure --pending\", which finds exactly the packages this file marks\n"
                + "  # \"install ok unpacked\". Left at its old \"installed\" state, an upgraded package was\n"
                + "  # never configured, apt reported \"planned for dpkg to do more than it reported back\",\n"
                + "  # and jq stayed at the old version.\n"
                + "  record_state \"$name\" \"install ok unpacked\" \"$ver\" \"$arch\"\n"
                + "  emit \"unpacked\" \"$name:$arch\"\n"
                + "}\n"
                + "\n"
                + "pkg_version() {\n"
                + "  awk -v p=\"$1\" 'BEGIN{RS=\"\"} $0 ~ \"^Package: \"p\"$\" {\n"
                + "      for (i = 1; i <= NF; i++) if ($i == \"Version:\") { print $(i+1); exit }\n"
                + "    }' \"$DB\" 2>/dev/null\n"
                + "}\n"
                + "\n"
                + "pkg_arch() {\n"
                + "  awk -v p=\"$1\" 'BEGIN{RS=\"\";FS=\"\\n\"} {\n"
                + "      pkg=\"\"; arch=\"\"\n"
                + "      for (i = 1; i <= NF; i++) {\n"
                + "        if ($i ~ /^Package: /) pkg = substr($i, 10)\n"
                + "        else if ($i ~ /^Architecture: /) arch = substr($i, 15)\n"
                + "      }\n"
                + "      if (pkg == p) { print arch; exit }\n"
                + "    }' \"$DB\" 2>/dev/null\n"
                + "}\n"
                + "\n"
                + "record_state() {  # record_state <pkg> <status line> <version> <arch>\n"
                + "  local pkg=\"$1\" want=\"$2\" ver=\"$3\" arch=\"$4\"\n"
                + "  # First install into an empty prefix: awk cannot read a missing file and the\n"
                + "  # END stanza would never be written, so create it first.\n"
                + "  [ -f \"$DB\" ] || : > \"$DB\"\n"
                + "  awk -v pkg=\"$pkg\" -v want=\"$want\" -v ver=\"$ver\" -v arch=\"$arch\" '\n"
                + "    BEGIN { RS = \"\"; ORS = \"\\n\\n\"; found = 0 }\n"
                + "    {\n"
                + "      # Paragraph mode puts a whole stanza in $0, so \"$\" can never match a single line;\n"
                + "      # compare the first line instead.\n"
                + "      split($0, L, \"\\n\")\n"
                + "      if (L[1] == \"Package: \" pkg) {\n"
                + "        found = 1\n"
                + "        if ($0 ~ /Status:/) sub(/Status: [^\\n]*/, \"Status: \" want)\n"
                + "        else $0 = $0 \"\\nStatus: \" want\n"
                + "        if (ver != \"\") {\n"
                + "          if ($0 ~ /Version:/) sub(/Version: [^\\n]*/, \"Version: \" ver)\n"
                + "          else $0 = $0 \"\\nVersion: \" ver\n"
                + "        }\n"
                + "        if (arch != \"\") {\n"
                + "          if ($0 ~ /Architecture:/) sub(/Architecture: [^\\n]*/, \"Architecture: \" arch)\n"
                + "          else $0 = $0 \"\\nArchitecture: \" arch\n"
                + "        }\n"
                + "      }\n"
                + "      print\n"
                + "    }\n"
                + "    END {\n"
                + "      if (!found)\n"
                + "        printf \"Package: %s\\nStatus: %s\\nVersion: %s\\nArchitecture: %s\\n\\n\",\n"
                + "               pkg, want, ver, arch\n"
                + "    }\n"
                + "  ' \"$DB\" > \"$DB.tmp\" 2>/dev/null && mv \"$DB.tmp\" \"$DB\"\n"
                + "}\n"
                + "\n"
                + "configure_one() {\n"
                + "  local name=\"${1:-}\"\n"
                + "  [ -z \"$name\" ] && return 0\n"
                + "  name=\"${name%%:*}\"\n"
                + "  local arch; arch=$(pkg_arch \"$name\"); [ -z \"$arch\" ] && arch=aarch64\n"
                + "  local ver; ver=$(pkg_version \"$name\")\n"
                + "  local post=\"$PREFIX/var/lib/dpkg/info/$name.postinst\"\n"
                + "  emit \"half-configured\" \"$name:$arch\"\n"
                + "  if [ -f \"$post\" ]; then\n"
                + "    # dpkg runs these from / with the package's version as the argument.\n"
                + "    ( cd / && PREFIX=\"$PREFIX\" PATH=\"$PREFIX/bin:/system/bin\" \\\n"
                + "        LD_LIBRARY_PATH=\"$PREFIX/lib\" HOME=" + files + "/home \\\n"
                + "        sh \"$post\" configure \"$ver\" ) >/dev/null 2>&1 || true\n"
                + "  fi\n"
                + "  record_state \"$name\" \"install ok installed\" \"$ver\" \"$arch\"\n"
                + "  emit \"installed\" \"$name:$arch\"\n"
                + "}\n"
                + "\n"
                + "remove_one() {\n"
                + "  local name=\"${1:-}\" f\n"
                + "  [ -z \"$name\" ] && return 0\n"
                + "  # apt passes \"tree:aarch64\"; dpkg's own records and the status file use the bare name.\n"
                + "  name=\"${name%%:*}\"\n"
                + "  local arch; arch=$(pkg_arch \"$name\"); [ -z \"$arch\" ] && arch=aarch64\n"
                + "  local list=\"$PREFIX/var/lib/dpkg/info/$name.list\"\n"
                + "  emit \"half-configured\" \"$name:$arch\"\n"
                + "  if [ -f \"$list\" ]; then\n"
                + "    while IFS= read -r f; do\n"
                + "      [ -n \"$f\" ] && rm -f \"$PREFIX/$f\" 2>/dev/null\n"
                + "    done < \"$list\"\n"
                + "    # Drop directories that the removal left empty, deepest first.\n"
                + "    ( cd \"$PREFIX\" && find . -type d -empty 2>/dev/null | sort -r | while IFS= read -r d; do\n"
                + "        case \"$d\" in .|./bin|./lib|./etc|./var) ;; *) rmdir \"$PREFIX/$d\" 2>/dev/null ;; esac\n"
                + "      done )\n"
                + "    rm -f \"$list\" 2>/dev/null\n"
                + "  fi\n"
                + "  rm -f \"$PREFIX/var/lib/dpkg/info/$name.\"* 2>/dev/null\n"
                + "  # Mark it removed rather than deleting the stanza, so apt sees a coherent history.\n"
                + "  record_state \"$name\" \"deinstall ok config-files\" \"\" \"\"\n"
                + "  emit \"half-installed\" \"$name:$arch\"\n"
                + "}\n"
                + "\n"
                + "# Query commands. dpkg -l matters beyond convenience: the short form of\n"
                + "# \"dpkg --list\" is how this shim is asked to prove that an install actually landed.\n"
                + "list_packages() {\n"
                + "  local want\n"
                + "  [ $# -eq 0 ] && set -- '*'\n"
                + "  echo 'Desired=Unknown/Install/Remove/Purge/Hold'\n"
                + "  echo '| Status=Not/Inst/Conf-files/Unpacked/halF-conf/Half-inst/trig-aWait/Trig-pend'\n"
                + "  echo '|/ Err?=(none)/Reinst-required (Status,Err: uppercase=bad)'\n"
                + "  echo '||/ Name                     Version          Architecture'\n"
                + "  echo '+++-========================-================-================'\n"
                + "  for want in \"$@\"; do\n"
                + "    awk -v want=\"$want\" '\n"
                + "      BEGIN { RS = \"\"; FS = \"\\n\" }\n"
                + "      {\n"
                + "        pkg = \"\"; status = \"\"; ver = \"\"; arch = \"\"\n"
                + "        for (i = 1; i <= NF; i++) {\n"
                + "          if ($i ~ /^Package: /) pkg = substr($i, 10)\n"
                + "          else if ($i ~ /^Status: /) status = substr($i, 9)\n"
                + "          else if ($i ~ /^Version: /) ver = substr($i, 10)\n"
                + "          else if ($i ~ /^Architecture: /) arch = substr($i, 15)\n"
                + "        }\n"
                + "        if (pkg == \"\") next\n"
                + "        if (want != \"*\" && pkg != want) next\n"
                + "        split(status, S, \" \")\n"
                + "        desired = \"u\"; state = \"n\"\n"
                + "        if (S[1] == \"install\") desired = \"i\"\n"
                + "        else if (S[1] == \"hold\") desired = \"h\"\n"
                + "        else if (S[1] == \"deinstall\") desired = \"r\"\n"
                + "        else if (S[1] == \"purge\") desired = \"p\"\n"
                + "        if (S[3] == \"installed\") state = \"i\"\n"
                + "        else if (S[3] == \"unpacked\") state = \"U\"\n"
                + "        else if (S[3] == \"half-configured\") state = \"F\"\n"
                + "        else if (S[3] == \"half-installed\") state = \"H\"\n"
                + "        else if (S[3] == \"config-files\") state = \"c\"\n"
                + "        printf \"%s%s  %-24s %-16s %s\\n\", desired, state, pkg, ver, arch\n"
                + "      }' \"$DB\" 2>/dev/null\n"
                + "  done\n"
                + "}\n"
                + "\n"
                + "show_status() {\n"
                + "  local want\n"
                + "  for want in \"$@\"; do\n"
                + "    awk -v want=\"$want\" '\n"
                + "      BEGIN { RS = \"\"; FS = \"\\n\" }\n"
                + "      {\n"
                + "        pkg = \"\"\n"
                + "        for (i = 1; i <= NF; i++)\n"
                + "          if ($i ~ /^Package: /) { pkg = substr($i, 10); break }\n"
                + "        if (pkg == want) print $0 \"\\n\"\n"
                + "      }' \"$DB\" 2>/dev/null\n"
                + "  done\n"
                + "}\n"
                + "\n"
                + "list_files() {\n"
                + "  local want list f\n"
                + "  for want in \"$@\"; do\n"
                + "    list=\"$PREFIX/var/lib/dpkg/info/${want%%:*}.list\"\n"
                + "    [ -f \"$list\" ] || continue\n"
                + "    while IFS= read -r f; do\n"
                + "      [ -n \"$f\" ] && echo \"$PREFIX/$f\"\n"
                + "    done < \"$list\"\n"
                + "  done\n"
                + "}\n"
                + "\n"
                + "case \"${1:-}\" in\n"
                + "  --version)\n"
                + "    echo \"Debian 'dpkg' (shim over dpkg-deb) 1.22\" ; exit 0 ;;\n"
                + "  --print-architecture)\n"
                + "    echo \"aarch64\" ; exit 0 ;;\n"
                + "  --print-foreign-architectures|--audit|--assert*)\n"
                + "    exit 0 ;;\n"
                + "  -l|--list)\n"
                + "    shift; list_packages \"$@\"; exit 0 ;;\n"
                + "  -s|--status)\n"
                + "    shift; show_status \"$@\"; exit 0 ;;\n"
                + "  -L|--listfiles)\n"
                + "    shift; list_files \"$@\"; exit 0 ;;\n"
                + "esac\n"
                + "\n"
                + "rc=0\n"
                + "MODE=\"\"\n"
                + "for ((i = 0; i < ${#args[@]}; i++)); do\n"
                + "  case \"${args[$i]}\" in\n"
                + "    --unpack | --install | -i | --auto-deconfigure) MODE=\"$MODE unpack\" ;;\n"
                + "    --configure) MODE=\"$MODE configure\" ;;\n"
                + "    --remove | --purge | -r | -P) MODE=\"$MODE remove\" ;;\n"
                + "    --set-selections) MODE=\"$MODE selections\" ;;\n"
                + "  esac\n"
                + "done\n"
                + "\n"
                + "# apt calls: --status-fd N --no-triggers --unpack --auto-deconfigure <deb>. Options may sit\n"
                + "# between the verb and the file, so scan the whole argv for .deb paths rather than parsing\n"
                + "# positionally.\n"
                + "if [[ \"$MODE\" == *unpack* ]]; then\n"
                + "  for a in \"$@\"; do\n"
                + "    case \"$a\" in\n"
                + "      *.deb) [ -f \"$a\" ] && { unpack_one \"$a\" || rc=$?; } ;;\n"
                + "    esac\n"
                + "  done\n"
                + "fi\n"
                + "\n"
                + "if [[ \"$MODE\" == *configure* ]]; then\n"
                + "  pending=0\n"
                + "  for a in \"$@\"; do\n"
                + "    case \"$a\" in --pending|-a) pending=1 ;; esac\n"
                + "  done\n"
                + "  if [ \"$pending\" = 1 ]; then\n"
                + "    for n in $(awk 'BEGIN{RS=\"\"} $0 ~ /Status: install ok unpacked/ {\n"
                + "          for (i=1;i<=NF;i++) if ($i==\"Package:\") { print $(i+1); break } }' \"$DB\"); do\n"
                + "      configure_one \"$n\"\n"
                + "    done\n"
                + "  else\n"
                + "    skip=0\n"
                + "    skipnext=0\n"
                + "    for a in \"$@\"; do\n"
                + "      if [ \"$skipnext\" = 1 ]; then skipnext=0; continue; fi\n"
                + "      case \"$a\" in\n"
                + "        --status-fd) skipnext=1 ;;\n"
                + "        --configure) skip=1 ;;\n"
                + "        -*) continue ;;\n"
                + "        *) [ \"$skip\" = 1 ] && configure_one \"$a\" ;;\n"
                + "      esac\n"
                + "    done\n"
                + "  fi\n"
                + "fi\n"
                + "\n"
                + "# apt asks dpkg to record the desired state with \"dpkg --set-selections\", feeding\n"
                + "# \"<package> deinstall\" lines on stdin; the actual removal then follows. Ignoring this made\n"
                + "# \"apt-get remove\" a no-op: apt believed the selection was recorded and never asked again.\n"
                + "if [[ \"$MODE\" == *selections* ]]; then\n"
                + "  while read -r pkg action; do\n"
                + "    [ -z \"${pkg:-}\" ] && continue\n"
                + "    case \"${action:-}\" in\n"
                + "      deinstall | purge) remove_one \"$pkg\" ;;\n"
                + "      install | hold | *) : ;;\n"
                + "    esac\n"
                + "  done\n"
                + "fi\n"
                + "\n"
                + "if [[ \"$MODE\" == *remove* ]]; then\n"
                + "  skipnext=0\n"
                + "  for a in \"$@\"; do\n"
                + "    if [ \"$skipnext\" = 1 ]; then skipnext=0; continue; fi\n"
                + "    case \"$a\" in\n"
                + "      --status-fd) skipnext=1 ;;\n"
                + "      -*) continue ;;\n"
                + "      *) remove_one \"$a\" ;;\n"
                + "    esac\n"
                + "  done\n"
                + "fi\n"
                + "\n"
                + "exit $rc\n";
    }

    /** The mirror pkg should use; the automatic choice was a Swedish host that times out here. */
    private static final String PKG_MIRROR = "https://mirrors.cernet.edu.cn/termux/apt/termux-main";

    /**
     * Make Termux's pkg stat this prefix's apt cache, and keep it off the mirror lottery.
     *
     * <p>pkg skips its full mirror sweep only when /data/data/com.termux/cache/apt/pkgcache.bin
     * was modified in the last six hours. That path is outside $PREFIX and never exists in
     * another app, so every "pkg install" re-tested every mirror URL in etc/termux/mirrors --
     * 71 checks, two to four minutes, and then it overwrote sources.list with whichever mirror
     * won the random weighted draw. The real cache is $PREFIX/var/cache/apt/pkgcache.bin, which
     * apt does write; pointing the check at it makes the second and later runs take the cheap
     * "is the current mirror still up" branch.
     *
     * <p>This runs from writeAptConfig on every start, and rewrites only when the text differs,
     * so it also repairs a prefix that was installed from an older APK.
     */
    private static void fixPkgCachePath(Context ctx) {
        File pkg = new File(prefix(ctx), "bin/pkg");
        if (!pkg.isFile()) return;
        String cache = new File(prefix(ctx), "var/cache/apt/pkgcache.bin").getAbsolutePath();
        String archives = new File(prefix(ctx), "var/cache/apt/archives").getAbsolutePath();
        try {
            String have = new String(java.nio.file.Files.readAllBytes(pkg.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String fixed = have
                    .replace("/data/data/com.termux/cache/apt/pkgcache.bin", cache)
                    .replace("/data/data/com.termux/cache/apt/archives", archives);
            if (!fixed.equals(have)) {
                java.nio.file.Files.write(pkg.toPath(), fixed.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
                //noinspection ResultOfMethodCallIgnored
                pkg.setExecutable(true, false);
                LogFile.log(TAG, "pkg: cache checks repointed at " + cache);
            }
        } catch (IOException e) {
            LogFile.log(TAG, "pkg: could not rewrite cache paths: " + e);
        }
    }

    /**
     * Pin pkg and apt to one mirror.
     *
     * <p>Even with the cache path fixed, a cache older than six hours sends pkg through the
     * weighted random pick again, and on this network that selected a host that times out. Two
     * files make the choice deterministic: chosen_mirrors, which pkg's select_mirror() reads
     * before scanning the mirror tree (a symlink to the cernet definition, the shape
     * termux-change-repo writes), and sources.list, which apt itself reads.
     */
    private static void pinMirror(Context ctx) {
        File prefix = prefix(ctx);
        File mirror = new File(prefix, "etc/termux/mirrors/chinese_mainland/mirrors.cernet.edu.cn");
        File chosen = new File(prefix, "etc/termux/chosen_mirrors");
        try {
            if (!mirror.isFile()) return;
            boolean pinned = java.nio.file.Files.isSymbolicLink(chosen.toPath())
                    && java.nio.file.Files.readSymbolicLink(chosen.toPath()).toString()
                            .equals(mirror.getAbsolutePath());
            if (!pinned) {
                // A symlink left by an earlier run (or a hand edit) points somewhere else; drop
                // it so the link can be replaced. Directories are a user's mirror group and are
                // left alone.
                if (java.nio.file.Files.isSymbolicLink(chosen.toPath()) || chosen.isFile()) {
                    java.nio.file.Files.deleteIfExists(chosen.toPath());
                }
                if (!chosen.exists()) {
                    android.system.Os.symlink(mirror.getAbsolutePath(), chosen.getAbsolutePath());
                    LogFile.log(TAG, "pkg: pinned the mirror to " + PKG_MIRROR);
                }
            }
        } catch (Throwable e) {
            LogFile.log(TAG, "pkg: could not pin chosen_mirrors: " + e);
        }
        File sources = new File(prefix, "etc/apt/sources.list");
        String want = "# The main termux repository\n"
                + "deb " + PKG_MIRROR + " stable main\n";
        try {
            String have = sources.isFile()
                    ? new String(java.nio.file.Files.readAllBytes(sources.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8) : "";
            // Only when it is not already ours: rewriting always would bump the mtime that
            // pkg's update_apt_cache() compares against pkgcache.bin.
            if (!have.contains(PKG_MIRROR)) {
                try (java.io.FileOutputStream w = new java.io.FileOutputStream(sources)) {
                    w.write(want.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                LogFile.log(TAG, "apt: sources.list pinned to " + PKG_MIRROR);
            }
        } catch (IOException e) {
            LogFile.log(TAG, "apt: could not pin sources.list: " + e);
        }
    }

    private static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";

    /**
     * Rewrite shebangs that point at Termux's own prefix.
     *
     * <p>Binaries are relocatable (they use $ORIGIN in DT_RUNPATH), but scripts are not: apt-key,
     * pkg, ssh-* and friends start with {@code #!/data/data/com.termux/files/usr/bin/bash}, and
     * in another app that interpreter does not exist, so apt reports "Couldn't execute ...
     * apt-key ... repository is not signed". Any script whose first line names the Termux prefix
     * is repointed at this prefix instead.
     */
    private static int rewriteShebangs(File prefix) {
        int n = 0;
        for (String sub : new String[]{"bin", "libexec", "lib/apt/methods"}) {
            File dir = new File(prefix, sub);
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File f : children) {
                if (f.isDirectory() || f.length() > 512 * 1024) continue;
                try {
                    byte[] head = new byte[64];
                    int got;
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        got = in.read(head);
                    }
                    if (got < 4 || head[0] != '#' || head[1] != '!') continue;
                    String first = new String(head, 0, got, java.nio.charset.StandardCharsets.UTF_8);
                    // Only scripts are rewritten: an ELF that happens to contain the string gets
                    // corrupted by a text replacement of a different length.
                    if (!first.startsWith("#!")) continue;
                    byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                    String text = new String(all, java.nio.charset.StandardCharsets.UTF_8);
                    // Replace the prefix everywhere, not only in the shebang: a survey of this
                    // prefix found 43 scripts naming it, and most do so in ordinary statements
                    // (pkg, termux-* helpers) rather than on the first line.
                    String fixed = text.replace(TERMUX_PREFIX, prefix.getAbsolutePath());
                    if (fixed.equals(text)) continue;
                    java.nio.file.Files.write(f.toPath(),
                            fixed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    n++;
                } catch (Throwable ignored) {
                    // A binary that merely starts with '#' is not a script; leave it alone.
                }
            }
        }
        return n;
    }

    private static int makeExecutable(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int n = 0;
        for (File f : children) {
            if (f.isDirectory()) {
                n += makeExecutable(f);
            } else if (f.setExecutable(true, false)) {
                n++;
            }
        }
        return n;
    }
}
