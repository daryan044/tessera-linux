// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun `a later version is newer, by its numbers`() {
        assertTrue(Updates.newer("0.8.0", "0.7.0"))
        assertTrue(Updates.newer("0.7.1", "0.7.0"))
        assertTrue(Updates.newer("0.10.0", "0.9.5"))
        assertTrue(Updates.newer("1.0", "0.9.9"))
        assertTrue(Updates.newer("v0.7.1", "0.7.0"))
        assertFalse(Updates.newer("0.7.0", "0.7.0"))
        assertFalse(Updates.newer("0.7", "0.7.0"))
        assertFalse(Updates.newer("0.6.3", "0.7.0"))
        // A build with a suffix counts by its numbers; what can't be read is never an update.
        assertFalse(Updates.newer("0.7.0", "0.7.0-debug"))
        assertTrue(Updates.newer("0.7.1", "0.7.0-debug"))
        assertFalse(Updates.newer("nightly", "0.7.0"))
        assertFalse(Updates.newer("0.8.0", "unknown"))
    }

    @Test fun `GitHub's latest release is read`() {
        val release = Updates.parse(
            """{"tag_name": "v0.8.0", "name": "Tessera 0.8.0", "prerelease": false,
               "html_url": "https://github.com/daryan044/tessera-linux/releases/tag/v0.8.0",
               "body": "## New\n- AppImages\n"}""",
        )
        assertEquals(Release("0.8.0", "https://github.com/daryan044/tessera-linux/releases/tag/v0.8.0", "## New\n- AppImages"), release)
    }

    @Test fun `a link that isn't the project's is replaced, and other answers are no release`() {
        val release = Updates.parse("""{"tag_name": "v0.8.0", "html_url": "https://example.com/get"}""")
        assertEquals("https://github.com/daryan044/tessera-linux/releases/tag/v0.8.0", release?.url)
        assertNull(Updates.parse("""{"message": "API rate limit exceeded"}"""))
        assertNull(Updates.parse("""{"tag_name": "nightly"}"""))
        assertNull(Updates.parse("<html>"))
    }
}
