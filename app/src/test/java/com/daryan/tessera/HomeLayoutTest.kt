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

    private fun widget(id: String, tab: String = "All") = HomeWidget(id, WidgetKind.STATS, tab)
    private fun keys(layout: HomeLayout, tab: String = "All") =
        homeItems(tab, arrange(apps, tab, layout), layout).map { it.key }

    @Test fun `a new widget leads its own tab and no other`() {
        val layout = HomeLayout(widgets = listOf(widget("w")))
        assertEquals(listOf("widget:w", "zebra", "Apple", "mango"), keys(layout))
        assertEquals(listOf("zebra", "Apple", "mango"), keys(layout, tab = "Games"))
    }

    @Test fun `a dragged widget stays ahead of the app that followed it`() {
        val layout = HomeLayout(
            sort = HomeSort.MANUAL,
            widgets = listOf(widget("w")),
            order = mapOf("All" to listOf("mango", "widget:w", "zebra", "Apple")),
        )
        assertEquals(listOf("mango", "widget:w", "zebra", "Apple"), keys(layout))
    }

    @Test fun `a widget dragged to the end stays there, and new widgets still lead`() {
        val layout = HomeLayout(
            sort = HomeSort.MANUAL,
            widgets = listOf(widget("old"), widget("new")),
            order = mapOf("All" to listOf("mango", "zebra", "Apple", "widget:old")),
        )
        assertEquals(listOf("widget:new", "mango", "zebra", "Apple", "widget:old"), keys(layout))
    }

    @Test fun `two widgets dragged side by side keep their order`() {
        val layout = HomeLayout(
            sort = HomeSort.MANUAL,
            widgets = listOf(widget("b"), widget("a")),
            order = mapOf("All" to listOf("mango", "widget:a", "widget:b", "zebra", "Apple")),
        )
        assertEquals(listOf("mango", "widget:a", "widget:b", "zebra", "Apple"), keys(layout))
    }

    @Test fun `a widget's place outlives the app it was next to`() {
        val layout = HomeLayout(
            sort = HomeSort.MANUAL,
            widgets = listOf(widget("w")),
            order = mapOf("All" to listOf("mango", "widget:w", "uninstalled", "zebra", "Apple")),
        )
        assertEquals(listOf("mango", "widget:w", "zebra", "Apple"), keys(layout))
    }

    @Test fun `any other sort leaves widgets leading, and each keeps its own settings`() {
        val a = HomeWidget("a", WidgetKind.APP, "All", WidgetTarget("gimp", "GIMP"), wide = false)
        val b = HomeWidget("b", WidgetKind.APP, "All", WidgetTarget("mango", "mango"))
        val items = homeItems("All", arrange(apps, "All", HomeLayout(sort = HomeSort.NAME)), HomeLayout(sort = HomeSort.NAME, widgets = listOf(a, b)))
        assertEquals(listOf("widget:a", "widget:b", "Apple", "mango", "zebra"), items.map { it.key })
        assertEquals(a, (items[0] as HomeItem.Widget).widget)
        assertEquals(b, (items[1] as HomeItem.Widget).widget)
    }

    @Test fun `a saved layout comes back, and one from a newer version still reads`() {
        val json = Json { ignoreUnknownKeys = true }
        val layout = HomeLayout(columns = 5, iconSize = HomeIconSize.LARGE, sort = HomeSort.RECENT, hidden = setOf("a"), iconColors = mapOf("a" to -65536))
        assertEquals(layout, json.decodeFromString(HomeLayout.serializer(), json.encodeToString(HomeLayout.serializer(), layout)))
        assertEquals(HomeLayout(columns = 4), json.decodeFromString(HomeLayout.serializer(), """{"columns":4,"fromTheFuture":true}"""))
    }
}
