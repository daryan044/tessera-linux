// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

/**
 * What every root shell runs first: busybox's tools on PATH after the phone's own, so a command the
 * phone's root shell can't reach still runs. Some phones' root shell reaches none of toybox's (no
 * unshare, tr, touch or printf on a Galaxy Z Fold5 with KernelSU, #5; no printf on a Redmi Pad 2
 * with SukiSU, #7), and naming the tools one at a time never caught them all.
 *
 * Every busybox applet is linked by name into [DIR], once per boot: busybox runs the applet its
 * link is called by, whatever its build. /dev is a tmpfs only root can write to, so no app can put
 * a program of its own there, and a reboot empties it. After the phone's own on PATH, a phone that
 * has a tool keeps using it; chroot/enter.sh and flatpak.sh put [DIR] first instead.
 */
object RootShell {
    /** The busybox Magisk, KernelSU (and its forks) and APatch ship, in the order looked for. */
    val BUSYBOXES = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")

    /** Where busybox's applets are linked; .busybox in it names the busybox they point to. */
    const val DIR = "/dev/tessera-busybox"

    /**
     * The shell for it, builtins only until the links are there, and silent: callers read what
     * their command prints, errors included. The links are made again, over the old ones, when the
     * busybox found isn't the one they point to (the root manager changed). Not with
     * `busybox --install`: it needs FEATURE_INSTALLER, and leaves a link that's already there as it
     * is. Without a busybox, PATH is left as it is.
     */
    fun prelude(busyboxes: List<String> = BUSYBOXES, dir: String = DIR): String {
        val d = shellQuote(dir)
        return "for _tb in ${busyboxes.joinToString(" ") { shellQuote(it) }}; do [ -x \"\$_tb\" ] && break; done; " +
            "if [ -x \"\$_tb\" ]; then " +
            "_tok=; read -r _tok 2>/dev/null <$d/.busybox || true; " +
            "if [ \"\$_tok\" != \"\$_tb\" ] && \"\$_tb\" mkdir -p $d 2>/dev/null; then " +
            "for _ta in \$(\"\$_tb\" --list); do \"\$_tb\" ln -sf \"\$_tb\" $d/\"\$_ta\" 2>/dev/null; done; " +
            "echo \"\$_tb\" 2>/dev/null >$d/.busybox; fi; " +
            "PATH=\"\${PATH:+\$PATH:}\"$d; fi; "
    }

    val PRELUDE = prelude()

    /** [command] for `su -c`, with [PRELUDE] in front. */
    fun line(command: String) = PRELUDE + command
}
