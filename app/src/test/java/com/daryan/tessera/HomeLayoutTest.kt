// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import com.daryan.tessera.container.PermissionManifest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    private fun app(id: String, category: String = "Apps") =
        PermissionManifest(id = id, name = id, exec = listOf(id), category = category)

    private val apps = listOf(app("zebra", "Games"), app("Apple", "Office"), app("mango", "Games"))

    private fun ids(layout: HomeLayout, pinned: List<String> = emptyList(), tab: String = "All", from: List<PermissionManifest> = apps) =
        arrange(from, tab, layout, pinned).map { it.id }

    @Test fun `by default a tab keeps the order it came in`() {
        assertEquals(listOf("zebra", "Apple", "mango"), ids(HomeLayout()))
    }

    @Test fun `names sort regardless of case, both ways`() {
        assertEquals(listOf("Apple", "mango", "zebra"), ids(HomeLayout(sort = HomeSort.NAME)))
        assertEquals(listOf("zebra", "mango", "Apple"), ids(HomeLayout(sort = HomeSort.NAME_REVERSED)))
    }

    @Test fun `category groups apps, by name within each`() {
        assertEquals(listOf("mango", "zebra", "Apple"), ids(HomeLayout(sort = HomeSort.CATEGORY)))
    }

    @Test fun `recently used comes first, and the never used follow by name`() {
        val layout = HomeLayout(sort = HomeSort.RECENT, lastUsed = mapOf("mango" to 5L, "zebra" to 9L))
        assertEquals(listOf("zebra", "mango", "Apple"), ids(layout))
        assertEquals(listOf("Apple", "mango", "zebra"), ids(HomeLayout(sort = HomeSort.RECENT)))
    }

    @Test fun `a dragged order is the tab's own, and other tabs keep theirs`() {
        val layout = HomeLayout(sort = HomeSort.MANUAL, order = mapOf("All" to listOf("mango", "zebra", "Apple"), "Games" to listOf("zebra", "mango")))
        assertEquals(listOf("mango", "zebra", "Apple"), ids(layout))
        assertEquals(listOf("zebra", "mango"), ids(layout, tab = "Games", from = apps.filter { it.category == "Games" }))
        // A tab nobody dragged stays as it came.
        assertEquals(listOf("zebra", "Apple", "mango"), ids(layout, tab = "Never dragged"))
    }

    @Test fun `apps newer than the drag follow it, and removed ones are forgotten`() {
        val layout = HomeLayout(sort = HomeSort.MANUAL, order = mapOf("All" to listOf("gone", "mango", "Apple")))
        assertEquals(listOf("mango", "Apple", "zebra"), ids(layout))
    }

    @Test fun `hidden apps are left out of every sort`() {
        for (sort in HomeSort.entries) {
            assertEquals(sort.name, false, "mango" in ids(HomeLayout(sort = sort, hidden = setOf("mango"))))
        }
    }

    @Test fun `pinned apps lead whatever the sort, in the order they were pinned`() {
        assertEquals(listOf("zebra", "mango", "Apple"), ids(HomeLayout(sort = HomeSort.NAME), pinned = listOf("zebra", "mango")))
        // Not in this tab, or hidden: nothing to lead with.
        assertEquals(listOf("Apple", "mango", "zebra"), ids(HomeLayout(sort = HomeSort.NAME), pinned = listOf("nowhere")))
        assertEquals(listOf("Apple", "mango"), ids(HomeLayout(sort = HomeSort.NAME, hidden = setOf("zebra")), pinned = listOf("zebra")))
    }

    @Test fun `a saved layout comes back, and one from a newer version still reads`() {
        val json = Json { ignoreUnknownKeys = true }
        val layout = HomeLayout(columns = 5, iconSize = HomeIconSize.LARGE, sort = HomeSort.RECENT, hidden = setOf("a"), iconColors = mapOf("a" to -65536))
        assertEquals(layout, json.decodeFromString(HomeLayout.serializer(), json.encodeToString(HomeLayout.serializer(), layout)))
        assertEquals(HomeLayout(columns = 4), json.decodeFromString(HomeLayout.serializer(), """{"columns":4,"fromTheFuture":true}"""))
    }
}
