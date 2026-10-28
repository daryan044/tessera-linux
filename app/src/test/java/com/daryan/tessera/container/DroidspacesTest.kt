// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DroidspacesTest {
    private val paths = ContainerPaths(
        nativeLibDir = File("/app/lib"),
        filesDir = File("/app/files"),
        cacheDir = File("/app/cache"),
        prootRootfs = File("/app/files/rootfs/debian"),
        chrootRootfs = File("/data/local/linux/debian"),
    )

    private val bridge = Droidspaces.bridgeBinds(paths)

    private val sample = """
        name=web
        hostname=web
        rootfs_path=/data/local/Droidspaces/Containers/web/rootfs.img
        net_mode=nat
        enable_android_storage=1
        enable_hw_access=0
        enable_gpu_mode=1
        bind_mounts=/srv/www:/var/www:ro
        some_unknown_key=kept
    """.trimIndent() + "\n"

    // ------------------------------------------------------------ config

    @Test fun `config keys Tessera cares about are read and the rest ignored`() {
        val c = DroidspacesConfig.parse("web", sample)
        assertEquals("web", c.name)
        assertEquals("/data/local/Droidspaces/Containers/web/rootfs.img", c.rootfs)
        assertTrue(c.isImage)
        assertTrue(c.androidStorage)
        assertFalse(c.hwAccess)
        assertTrue(c.gpu)
        assertTrue(c.hasGpuDevice)
        assertEquals("nat", c.netMode)
        assertEquals(listOf(DroidspacesBind("/srv/www", "/var/www", readOnly = true)), c.binds)
    }

    @Test fun `a directory rootfs is not an image and a missing config has defaults`() {
        assertFalse(DroidspacesConfig.parse("a", "rootfs_path=/data/local/Droidspaces/Containers/a/rootfs\n").isImage)
        val empty = DroidspacesConfig.parse("a", "")
        assertNull(empty.rootfs)
        assertEquals("nat", empty.netMode)
        assertTrue(empty.binds.isEmpty())
        assertFalse(empty.hasGpuDevice)
    }

    @Test fun `malformed bind entries are skipped`() {
        val binds = DroidspacesConfig.parseBinds("/a:/b,nonsense,/c:relative,:/d,/e:/f:ro")
        assertEquals(listOf(DroidspacesBind("/a", "/b"), DroidspacesBind("/e", "/f", readOnly = true)), binds)
    }

    @Test fun `bridge binds share every session and the script, read-only`() {
        assertEquals(
            listOf(
                DroidspacesBind("/app/cache/sessions", "/mnt/tessera/sessions"),
                DroidspacesBind("/app/files/droidspaces", "/mnt/tessera/bin", readOnly = true),
            ),
            bridge,
        )
    }

    @Test fun `adding the bridge keeps every other line and the existing binds`() {
        val out = DroidspacesConfig.withBinds(sample, bridge)
        val lines = out.lines()
        // Same lines, same order, only the bind line changed.
        assertEquals(sample.lines().map { if (it.startsWith("bind_mounts=")) "" else it }, lines.map { if (it.startsWith("bind_mounts=")) "" else it })
        val binds = DroidspacesConfig.parse("web", out).binds
        assertEquals(DroidspacesBind("/srv/www", "/var/www", readOnly = true), binds.first())
        assertTrue(DroidspacesConfig.parse("web", out).hasBinds(bridge))
        assertTrue(out.endsWith("\n"))
    }

    @Test fun `adding the bridge twice changes nothing and a moved source replaces the old one`() {
        val once = DroidspacesConfig.withBinds(sample, bridge)
        assertEquals(once, DroidspacesConfig.withBinds(once, bridge))
        val moved = bridge.map { it.copy(src = "/new" + it.src) }
        val binds = DroidspacesConfig.parse("web", DroidspacesConfig.withBinds(once, moved)).binds
        assertEquals(3, binds.size)
        assertTrue(binds.none { it.src.startsWith("/app/") })
    }

    @Test fun `a config with no bind line gets one at the end`() {
        val out = DroidspacesConfig.withBinds("name=a\nrootfs_path=/x\n", bridge)
        assertEquals(listOf("name=a", "rootfs_path=/x"), out.lines().take(2))
        assertTrue(out.lines()[2].startsWith("bind_mounts=/app/cache/sessions:/mnt/tessera/sessions,"))
        assertEquals("bind_mounts=/a:/b\n", DroidspacesConfig.withBinds("", listOf(DroidspacesBind("/a", "/b"))))
    }

    // ------------------------------------------------------------ what the container sees

    @Test fun `phone storage is visible only when the container has it`() {
        val on = DroidspacesConfig("c", androidStorage = true)
        val off = DroidspacesConfig("c")
        assertEquals("/storage/emulated/0/Download", DroidspacesView.visibleAt(on, "/storage/emulated/0/Download"))
        assertEquals("/storage/emulated/0", DroidspacesView.visibleAt(on, "/storage/emulated/0/"))
        assertNull(DroidspacesView.visibleAt(off, "/storage/emulated/0/Download"))
        assertNull(DroidspacesView.visibleAt(on, "/storage/emulated/0x"))
        assertNull(DroidspacesView.visibleAt(on, "/data/data/com.daryan.tessera/files"))
    }

    @Test fun `the storage links resolve to the same place`() {
        val on = DroidspacesConfig("c", androidStorage = true)
        assertEquals("/storage/emulated/0/DCIM", DroidspacesView.visibleAt(on, "/sdcard/DCIM"))
        assertEquals("/storage/emulated/0", DroidspacesView.visibleAt(on, "/sdcard"))
        assertEquals("/storage/emulated/0/Music", DroidspacesView.visibleAt(on, "/storage/self/primary/Music"))
    }

    @Test fun `the containers own binds name where a folder shows up`() {
        val c = DroidspacesConfig(
            "c",
            binds = listOf(DroidspacesBind("/storage/1234-ABCD", "/mnt/sd"), DroidspacesBind("/storage/1234-ABCD/Movies", "/movies")),
        )
        assertEquals("/mnt/sd/Docs", DroidspacesView.visibleAt(c, "/storage/1234-ABCD/Docs"))
        // The most specific bind wins.
        assertEquals("/movies/a.mkv", DroidspacesView.visibleAt(c, "/storage/1234-ABCD/Movies/a.mkv"))
    }

    // ------------------------------------------------------------ finding containers

    private val listing = """
        @@TESSERA debian
        @@PID 4242
        name=debian
        rootfs_path=/data/local/Droidspaces/Containers/debian/rootfs.img
        enable_android_storage=1
        bind_mounts=/app/cache/sessions:/mnt/tessera/sessions,/app/files/droidspaces:/mnt/tessera/bin:ro
        @@TESSERA Ubuntu-24
        @@PID NONE
        name=Ubuntu-24
        rootfs_path=/data/local/Droidspaces/Containers/Ubuntu-24/rootfs
        net_mode=host
        @@TESSERA bad name; rm -rf /
        @@PID 77
        name=evil
    """.trimIndent()

    @Test fun `the listing gives running and stopped containers, sorted, and drops unusable names`() {
        val found = DroidspacesHost.parseListing(listing, bridge)
        assertEquals(listOf("debian", "Ubuntu-24"), found.map { it.name })
        val debian = found[0]
        assertTrue(debian.running)
        assertEquals(4242, debian.pid)
        assertTrue(debian.bridged)
        assertTrue(debian.config.androidStorage)
        val ubuntu = found[1]
        assertFalse(ubuntu.running)
        assertFalse(ubuntu.bridged)
        assertEquals("host", ubuntu.config.netMode)
    }

    @Test fun `files are reached through proc while running, and through the folder while stopped`() {
        val found = DroidspacesHost.parseListing(listing, bridge)
        assertEquals("/proc/4242/root", found[0].hostRoot)
        assertEquals("/data/local/Droidspaces/Containers/Ubuntu-24/rootfs", found[1].hostRoot)
        // A stopped image can't be read from outside.
        assertNull(found[1].copy(config = found[1].config.copy(rootfs = "/x/a.img")).hostRoot)
    }

    @Test fun `only plain names reach a root shell`() {
        assertTrue(Droidspaces.validName("Debian_12-x.1"))
        assertFalse(Droidspaces.validName(""))
        assertFalse(Droidspaces.validName(".."))
        assertFalse(Droidspaces.validName("a b"))
        assertFalse(Droidspaces.validName("a;b"))
        assertFalse(Droidspaces.validName("a'b"))
    }

    // ------------------------------------------------------------ launching

    private val su = "/system/bin/su"
    private val binary = "/data/local/Droidspaces/bin/droidspaces"
    // Android's android.util.Base64 is mocked in local JVM tests; use the JDK encoder here.
    private val testEncodeSettings: (String) -> String = {
        java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
    }
    private val container = DroidspacesContainer(
        name = "debian",
        config = DroidspacesConfig("debian", androidStorage = true, hwAccess = true),
        pid = 4242,
        bridged = true,
    )
    private val session = Session(id = "s3", display = 3)
    private val notes = PermissionManifest(
        id = "notes",
        name = "Notes",
        exec = listOf("/usr/bin/notes", "--new", "it's"),
        permissions = Permissions(
            network = false,
            display = true,
            filesystem = listOf(
                FsGrant("/storage/emulated/0/Download", "/home/user/Downloads"),
                FsGrant("/storage/emulated/0/Music", "/media/music", readOnly = true),
                FsGrant("/data/data/other/files", "/home/user/other"),
            ),
        ),
    )

    /** The launch of [app], and the settings it hands enter.sh. */
    private fun launch(app: PermissionManifest, uid: Int = 10484, c: DroidspacesContainer = container): Pair<LaunchSpec, String> {
        val backend = DroidspacesBackend(paths, su, binary, c, appUid = uid, encodeSettings = testEncodeSettings)
        return backend.buildLaunch(app, session) to backend.settings(app, session)
    }

    @Test fun `an app starts through droidspaces run around the in-container script`() {
        val (spec, settings) = launch(notes)
        assertEquals(su, spec.executable)
        assertEquals(listOf(su, "-c"), spec.argv.take(2))
        assertEquals(
            "exec $binary --name=debian run -- /bin/sh /mnt/tessera/bin/enter.sh s3 ${shellQuote(testEncodeSettings(settings))}",
            spec.argv[2],
        )
        assertFalse('\n' in spec.argv[2])
        assertEquals(settings, String(java.util.Base64.getDecoder().decode(testEncodeSettings(settings)), Charsets.UTF_8))
    }

    @Test fun `the launch settings come from root's command line, not a file the apps could rewrite`() {
        val (spec, settings) = launch(notes)
        assertTrue(settings.startsWith("BINDS="))
        assertFalse("launch.env" in spec.argv[2])
    }

    @Test fun `settings follow the manifest`() {
        val env = parseEnv(launch(notes).second)
        assertEquals("1", env["X11"])
        assertEquals("1", env["NO_NET"])
        assertEquals("", env["NET_GROUPS"])
        assertEquals("0", env["RUN_AS_ROOT"])
        assertEquals("10484", env["APP_UID"])
        assertTrue("LAUNCHER_SESSION=s3" in env.getValue("GUEST_ENV").lines())
        val online = parseEnv(launch(notes.copy(permissions = notes.permissions.copy(network = true))).second)
        assertEquals("", online["NO_NET"])
        assertEquals("3003,3004", online["NET_GROUPS"])
        val root = parseEnv(launch(notes.copy(permissions = notes.permissions.copy(root = true))).second)
        assertEquals("1", root["RUN_AS_ROOT"])
    }

    @Test fun `folders the container can't see are left out and reported`() {
        val env = parseEnv(launch(notes).second)
        assertEquals(
            listOf(
                "/storage/emulated/0/Download:/home/user/Downloads:rw",
                "/storage/emulated/0/Music:/media/music:ro",
            ),
            env.getValue("BINDS").lines(),
        )
        val backend = DroidspacesBackend(paths, su, binary, container, appUid = 1)
        assertEquals(listOf("/home/user/other"), backend.unreachableGrants(notes).map { it.mountAt })
        val noStorage = container.copy(config = container.config.copy(androidStorage = false))
        assertEquals(3, DroidspacesBackend(paths, su, binary, noStorage, appUid = 1).unreachableGrants(notes).size)
    }

    @Test fun `the gpu is offered only to a container that has its node`() {
        assertTrue(DroidspacesBackend(paths, su, binary, container, appUid = 1).hasGpuDevice)
        val bare = container.copy(config = DroidspacesConfig("debian"))
        assertFalse(DroidspacesBackend(paths, su, binary, bare, appUid = 1).hasGpuDevice)
    }

    @Test fun `root apps are advisory and the rest enforced`() {
        val b = DroidspacesBackend(paths, su, binary, container, appUid = 1)
        assertEquals(Enforcement.ENFORCED, b.enforcement(Capability.NETWORK, notes))
        val root = notes.copy(permissions = Permissions(root = true))
        assertEquals(Enforcement.ADVISORY, b.enforcement(Capability.FILESYSTEM, root))
    }

    @Test fun `the settings survive a real shell to the byte`() {
        assumeTrue(File("/bin/sh").exists())
        val tricky = notes.copy(exec = listOf("/bin/sh", "-c", "echo \"a b\" 'c'; echo \$HOME \\ `x` \$(y)", "it's"))
        val text = launch(tricky).second
        // Handed over and read the way enter.sh gets them, printing each variable NUL-terminated.
        val script = "set -- s3 ${shellQuote(text)}; eval \"\$2\"; printf '%s\\0' \"\$CMD\" \"\$GUEST_ENV\" \"\$BINDS\""
        val p = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val parts = p.inputStream.readBytes().toString(Charsets.UTF_8).split('\u0000')
        p.waitFor()
        assertEquals(tricky.exec.joinToString(" ") { shellQuote(it) }, parts[0])
        assertEquals(parseEnv(text).getValue("GUEST_ENV"), parts[1])
        assertEquals(parseEnv(text).getValue("BINDS"), parts[2])
    }

    /** The KEY='value' lines of a launch's settings, unquoted the way sh does (single quotes, '\'' for one inside). */
    private fun parseEnv(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val eq = rest.indexOf('=')
            val key = rest.substring(0, eq)
            var i = eq + 1
            val value = StringBuilder()
            if (rest[i] == '\'') {
                // A quoted run, then maybe '\'' and more runs, until a newline outside quotes.
                while (i < rest.length && rest[i] != '\n') {
                    when {
                        rest[i] == '\'' -> {
                            val end = rest.indexOf('\'', i + 1)
                            value.append(rest, i + 1, end)
                            i = end + 1
                        }
                        rest[i] == '\\' && i + 1 < rest.length -> { value.append(rest[i + 1]); i += 2 }
                        else -> { value.append(rest[i]); i++ }
                    }
                }
            } else {
                while (i < rest.length && rest[i] != '\n') value.append(rest[i++])
            }
            out[key] = value.toString()
            rest = rest.substring(minOf(i + 1, rest.length))
        }
        return out
    }
}
