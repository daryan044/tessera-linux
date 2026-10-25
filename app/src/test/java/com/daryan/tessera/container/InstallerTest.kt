// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OciRegistryTest {
    private val index = Json.parseToJsonElement(
        """
        {"manifests": [
          {"digest": "sha256:amd", "platform": {"architecture": "amd64", "os": "linux"}},
          {"digest": "sha256:att", "platform": {"architecture": "unknown", "os": "unknown"}},
          {"digest": "sha256:v5",  "platform": {"architecture": "arm", "os": "linux", "variant": "v5"}},
          {"digest": "sha256:v7",  "platform": {"architecture": "arm", "os": "linux", "variant": "v7"}},
          {"digest": "sha256:a64", "platform": {"architecture": "arm64", "os": "linux", "variant": "v8"}}
        ]}
        """,
    ).jsonObject

    @Test fun `picks the manifest for each Android ABI`() {
        assertEquals("sha256:a64", OciRegistry.selectManifest(index, Platform.forAbi("arm64-v8a")))
        assertEquals("sha256:v7", OciRegistry.selectManifest(index, Platform.forAbi("armeabi-v7a")))
        assertEquals("sha256:amd", OciRegistry.selectManifest(index, Platform.forAbi("x86_64")))
    }

    @Test fun `missing platform is an error, not a wrong download`() {
        assertThrows(IOException::class.java) { OciRegistry.selectManifest(index, Platform("riscv64")) }
    }

    @Test fun `only single gzip layers are accepted`() {
        val one = Json.parseToJsonElement(
            """{"layers": [{"mediaType": "application/vnd.oci.image.layer.v1.tar+gzip", "digest": "sha256:x", "size": 42}]}""",
        ).jsonObject
        assertEquals(Layer("sha256:x", 42), OciRegistry.singleLayer(one))

        val two = Json.parseToJsonElement(
            """{"layers": [{"mediaType": "a+gzip", "digest": "d", "size": 1}, {"mediaType": "a+gzip", "digest": "e", "size": 1}]}""",
        ).jsonObject
        assertThrows(IOException::class.java) { OciRegistry.singleLayer(two) }
    }
}

class RootfsSetupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `configures dns, apt and the app user idempotently`() {
        val root = tmp.newFolder("rootfs")
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "bin").mkdirs()
        File(root, "bin/bash").writeText("")
        File(root, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        File(root, "etc/group").writeText("root:x:0:\n")

        repeat(2) { RootfsSetup.configure(root, uid = 10483, gid = 10483) }

        assertTrue(File(root, "etc/resolv.conf").readText().contains("nameserver"))
        assertEquals("APT::Sandbox::User \"root\";\n", File(root, "etc/apt/apt.conf.d/99launcher").readText())
        val passwd = File(root, "etc/passwd").readLines()
        assertEquals(1, passwd.count { it.startsWith("user:") })
        assertTrue("user:x:10483:10483:User:/home/user:/bin/bash" in passwd)
        val group = File(root, "etc/group").readLines()
        assertTrue("aid_inet:x:3003:user" in group)
        assertTrue("aid_10483_all:x:60483:user" in group)
        assertEquals(1, group.count { it.startsWith("aid_inet:") })
        assertTrue(File(root, "home/user").isDirectory)
    }

    @Test fun `enables contrib and non-free in deb822 sources, once`() {
        val root = tmp.newFolder("deb")
        val src = File(root, "etc/apt/sources.list.d").apply { mkdirs() }
        File(src, "debian.sources").writeText(
            "Types: deb\nURIs: http://deb.debian.org/debian\nSuites: trixie trixie-updates\nComponents: main\n\n" +
                "Types: deb\nURIs: http://deb.debian.org/debian-security\nComponents: main contrib\n",
        )
        repeat(2) { RootfsSetup.enableAllComponents(File(root, "etc")) }
        val components = File(src, "debian.sources").readLines().filter { it.startsWith("Components:") }
        assertEquals(List(2) { "Components: main contrib non-free non-free-firmware" }, components)
        assertTrue(File(src, "debian.sources").readText().contains("Suites: trixie trixie-updates"))
    }

    @Test fun `icon names come from the command, then the id and packages`() {
        val app = PermissionManifest(
            id = "firefox-esr", name = "Firefox", exec = listOf("/usr/bin/firefox-esr"), packages = listOf("firefox-esr"),
        )
        assertEquals("firefox-esr", AppIcons.candidateNames(app).first())
        val python = PermissionManifest(id = "python", name = "Python", exec = listOf("/usr/bin/python3"), packages = listOf("python3"))
        assertEquals(listOf("python3", "python", "python3"), AppIcons.candidateNames(python))
    }

    @Test fun `alpine without bash gets sh`() {
        val root = tmp.newFolder("alpine")
        RootfsSetup.configure(root, 10001, 10001)
        assertTrue(File(root, "etc/passwd").readText().contains(":/home/user:/bin/sh"))
        assertFalse(File(root, "etc/apt").exists())
    }

    @Test fun `fake proc files cover every bound name`() {
        val dir = tmp.newFolder("proc")
        FakeProc.write(dir, cpus = 4, kernel = "6.1.99")
        FakeProc.NAMES.forEach { assertTrue("missing $it", File(dir, it).isFile) }
        val stat = File(dir, "stat").readLines()
        assertEquals(4, stat.count { Regex("cpu\\d+ .*").matches(it) })
        assertTrue(File(dir, "version").readText().startsWith("Linux version 6.1.99"))
    }

    @Test fun `cpuinfo names a core without SVE unless the kernel has it`() {
        val noSve = "processor\t: 0\nFeatures\t: fp asimd atomics sve2-less bf16\nCPU part\t: 0xd4e\n\nprocessor\t: 1\nCPU part\t: 0xd46\n"
        val faked = FakeProc.withoutSve(noSve)
        assertEquals(listOf("CPU part\t: 0xd41", "CPU part\t: 0xd41"), faked.lines().filter { it.startsWith("CPU part") })
        assertTrue("Features\t: fp asimd atomics sve2-less bf16" in faked)
        val sve = "Features\t: fp asimd sve sve2\nCPU part\t: 0xd4e\n"
        assertEquals(sve, FakeProc.withoutSve(sve))
    }

    @Test fun `proot binds fake proc over the real files`() {
        val paths = ContainerPaths(
            File("/app/lib"), File("/app/files"), File("/app/cache"),
            File("/app/files/rootfs/debian"), File("/data/local/linux/debian"),
        )
        val app = PermissionManifest(id = "htop", name = "htop", exec = listOf("/usr/bin/htop"))
        val argv = ProotBackend(paths, FakeProc.NAMES, exists = { true }).buildLaunch(app, Session("s1")).argv
        assertTrue("--bind=/app/files/proc/stat:/proc/stat" in argv)
        assertTrue(argv.indexOf("--bind=/proc") < argv.indexOf("--bind=/app/files/proc/stat:/proc/stat"))
    }

    @Test fun `recognises PRoot's seccomp kernel bug`() {
        // As reported from a Galaxy S24 Ultra on Android 16.
        val output = listOf(
            "proot info: It seems your kernel contains this bug: https://bugs.launchpad.net/ubuntu/+source/linux/+bug/1202161",
            "To workaround it, set the env. variable PROOT_NO_SECCOMP to 1.",
            "fatal error: see `libproot.so --help`.",
        )
        assertTrue(seccompBug(output))
        assertFalse(seccompBug(listOf("tar: Error is not recoverable: exiting now")))
    }

    @Test fun `proot goes without seccomp once the marker is there, for apps too`() {
        val files = tmp.newFolder("files")
        val paths = ContainerPaths(
            File("/app/lib"), files, File("/app/cache"), File(files, "rootfs/debian"), File("/data/local/linux/debian"),
        )
        val app = PermissionManifest(id = "htop", name = "htop", exec = listOf("/usr/bin/htop"))
        assertFalse("PROOT_NO_SECCOMP" in paths.prootEnv())
        paths.prootNoSeccompMarker.createNewFile()
        assertEquals("1", paths.prootEnv()["PROOT_NO_SECCOMP"])
        assertEquals("1", ProotBackend(paths, exists = { true }).buildLaunch(app, Session("s1")).env["PROOT_NO_SECCOMP"])
    }
}
