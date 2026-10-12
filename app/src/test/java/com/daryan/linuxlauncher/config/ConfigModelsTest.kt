// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.linuxlauncher.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigModelsTest {
    // Debian trixie's /etc/profile, verbatim.
    private val profile = """
        # /etc/profile: system-wide .profile file for the Bourne shell (sh(1))
        # and Bourne compatible shells (bash(1), ksh(1), ash(1), ...).

        if [ "${'$'}(id -u)" -eq 0 ]; then
          PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        else
          PATH="/usr/local/bin:/usr/bin:/bin:/usr/local/games:/usr/games"
        fi
        export PATH

        if [ "${'$'}{PS1-}" ]; then
          if [ "${'$'}{BASH-}" ] && [ "${'$'}BASH" != "/bin/sh" ]; then
            # The file bash.bashrc already sets the default PS1.
            if [ -f /etc/bash.bashrc ]; then
              . /etc/bash.bashrc
            fi
          fi
        fi
    """.trimIndent() + "\n"

    // The user's root .bashrc after the launcher fixed it, plus a few typical lines.
    private val bashrc = """
        # ~/.bashrc: executed by bash(1) for non-login shells.
        # alias ll='ls ${'$'}LS_OPTIONS -l'
        export LS_OPTIONS='--color=auto'
        eval "${'$'}(dircolors)"
        alias ls='ls ${'$'}LS_OPTIONS'
        alias it='echo it'\''s'
        export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
        export EDITOR=nano
    """.trimIndent() + "\n"

    private val sources = """
        Types: deb
        URIs: http://deb.debian.org/debian
        Suites: trixie trixie-updates
        Components: main contrib non-free non-free-firmware
        Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp

        Types: deb
        URIs: http://deb.debian.org/debian-security
        Suites: trixie-security
        Components: main
        Signed-By: /usr/share/keyrings/debian-archive-keyring.pgp
    """.trimIndent() + "\n"

    @Test fun `unedited files come back byte for byte`() {
        assertEquals(profile, ShellRc.parse(profile).render())
        assertEquals(bashrc, ShellRc.parse(bashrc).render())
        assertEquals(sources, AptSources.parse(sources).render())
        val resolv = "# by the launcher\nsearch lan\nnameserver 1.1.1.1\nnameserver 8.8.8.8\noptions edns0\n"
        assertEquals(resolv, ResolvConf.parse(resolv).render())
        val hosts = "127.0.0.1\tlocalhost\n::1     localhost ip6-localhost\n# comment\n"
        assertEquals(hosts, HostsFile.parse(hosts).render())
        val env = "LANG=C.UTF-8\nGREETING=\"hello world\"\n"
        assertEquals(env, EnvironmentFile.parse(env).render())
    }

    @Test fun `the form sees variables and aliases, and leaves code alone`() {
        val rc = ShellRc.parse(bashrc)
        assertEquals(
            listOf("LS_OPTIONS" to "--color=auto", "PATH" to "\$HOME/.local/bin:\$PATH", "EDITOR" to "nano"),
            rc.lines.filterIsInstance<RcLine.Export>().map { it.key to it.value },
        )
        assertEquals(
            listOf("ls" to "ls \$LS_OPTIONS", "it" to "echo it's"),
            rc.lines.filterIsInstance<RcLine.Alias>().map { it.name to it.command },
        )
        // eval "$(dircolors)" and the commented alias aren't variables or aliases.
        assertEquals(3, rc.otherCount)
        // In /etc/profile the PATH assignments are inside an if: not top-level, not editable.
        assertTrue(ShellRc.parse(profile).lines.none { it is RcLine.Export })
    }

    @Test fun `editing one variable rewrites only that line`() {
        val rc = ShellRc.parse(bashrc)
        val edited = rc.copy(lines = rc.lines.map { if (it is RcLine.Export && it.key == "EDITOR") it.copy(value = "vim") else it })
        val before = bashrc.lines()
        val after = edited.render().lines()
        assertEquals(before.size, after.size)
        assertEquals(listOf("export EDITOR=vim"), after.filterIndexed { i, l -> l != before[i] })

        val added = rc.copy(lines = rc.lines + RcLine.Export("JAVA_HOME", "/usr/lib/jvm/default java") + RcLine.Alias("ll", "ls -l"))
        assertTrue(added.render().endsWith("export JAVA_HOME=\"/usr/lib/jvm/default java\"\nalias ll='ls -l'\n"))
    }

    @Test fun `apt sources become cards and back`() {
        val s = AptSources.parse(sources)
        assertEquals(2, s.sources.size)
        assertEquals(listOf("main", "contrib", "non-free", "non-free-firmware"), s.sources[0].components)
        assertEquals("trixie-security", s.sources[1].suites)

        val edited = s.copy(sources = listOf(s.sources[0], s.sources[1].copy(enabled = false), AptSource(suites = "trixie-backports")))
        val text = edited.render()
        assertTrue(text, text.contains("Enabled: no\nTypes: deb\nURIs: http://deb.debian.org/debian-security"))
        assertTrue(text.contains("Suites: trixie-backports\nComponents: main\n"))
        // The untouched first stanza is unchanged.
        assertTrue(text.startsWith(sources.substringBefore("\n\n")))
    }

    @Test fun `values that aren't plain words stay as untouched lines`() {
        assertNull(unquoteShell("\"\$(cat /etc/x)\""))
        assertNull(unquoteShell("foo; rm -rf x"))
        assertEquals("a b", unquoteShell("'a b'"))
        assertEquals("say \"hi\"", unquoteShell("\"say \\\"hi\\\"\""))
    }

    @Test fun `half-typed rows survive a round trip`() {
        val rc = ShellRc(listOf(RcLine.Export("", "x"), RcLine.Alias("", "")))
        val again = ShellRc.parse(rc.render())
        assertEquals(2, again.lines.count { it is RcLine.Export || it is RcLine.Alias })
    }
}
