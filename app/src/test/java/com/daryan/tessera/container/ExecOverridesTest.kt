// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExecOverridesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = PermissionManifest(
        id = "system-com.microsoft.vscode", name = "Code", exec = listOf("/usr/share/code/code"), gui = true,
        permissions = Permissions(network = true, display = true, audio = true),
    )

    @Test fun `overrides apply, persist, and drop when back to the default`() {
        val file = File(tmp.root, "exec.json")
        val store = ExecOverrides(file)
        val sandboxOff = listOf("/usr/share/code/code", "--no-sandbox")

        assertTrue(store.set(app, sandboxOff, app.exec).isEmpty())
        assertEquals(sandboxOff, store.apply(app).exec)
        // Read back by a fresh store, as after a restart.
        assertEquals(sandboxOff, ExecOverrides(file).apply(app).exec)

        store.set(app, app.exec, app.exec)
        assertEquals(emptyMap<String, List<String>>(), store.overrides.value)
    }

    @Test fun `an empty command is refused`() {
        val store = ExecOverrides(File(tmp.root, "exec.json"))
        assertTrue(store.set(app, emptyList(), app.exec).isNotEmpty())
        assertEquals(app, store.apply(app))
    }

    @Test fun `a command survives the round trip through one line`() {
        val exec = listOf("/usr/bin/env", "--no-sandbox", "an argument with spaces", "it's", "a \"quote\"")
        assertEquals(exec, ExecLine.parse(ExecLine.format(exec)))
    }

    @Test fun `quotes hold an argument together and are not passed on`() {
        assertEquals(
            listOf("/usr/share/code/code", "--no-sandbox", "/home/user/My Project"),
            ExecLine.parse("""  /usr/share/code/code   --no-sandbox  "/home/user/My Project"  """),
        )
        assertEquals(listOf("a b", "c"), ExecLine.parse("""'a b' c"""))
        assertEquals(listOf("a b"), ExecLine.parse("""a\ b"""))
        // '' is an argument of its own, not nothing.
        assertEquals(listOf("code", ""), ExecLine.parse("""code ''"""))
        assertEquals(emptyList<String>(), ExecLine.parse("   "))
    }
}
