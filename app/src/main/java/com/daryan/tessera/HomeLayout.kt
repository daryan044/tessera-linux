// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera

import android.content.Context
import com.daryan.tessera.container.PermissionManifest
import com.daryan.tessera.ui.customIconColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a tab's apps are put in order. Pinned apps lead whichever it is. */
@Serializable
enum class HomeSort(val label: String) {
    DEFAULT("Default"),
    NAME("A to Z"),
    NAME_REVERSED("Z to A"),
    RECENT("Recently used"),
    CATEGORY("Category"),
    MANUAL("Your order"),
}

/** How big icons are on the home screen. */
@Serializable
enum class HomeIconSize(val label: String, val iconDp: Int, val glyphDp: Int) {
    SMALL("Small", 56, 24),
    MEDIUM("Medium", 72, 30),
    LARGE("Large", 88, 36),
}

/** The widgets Tessera has, on its own home screen and the phone's alike. */
@Serializable
enum class WidgetKind(val label: String, val description: String, val needsTarget: Boolean = false) {
    APP("App", "Start one app", needsTarget = true),
    RUNNING("Running apps", "What's open in Linux now"),
    STATS("This phone", "Memory, storage and running apps"),
    COMMAND("Quick command", "Run one of your shortcuts", needsTarget = true),
}

/** What a widget starts: an app or shortcut, named so it can be drawn without looking it up. */
@Serializable
data class WidgetTarget(val appId: String, val name: String)

/** A widget on one of Tessera's tabs, with its own settings. */
@Serializable
data class HomeWidget(
    val id: String,
    val kind: WidgetKind,
    val tab: String,
    val target: WidgetTarget? = null,
    /** Across the whole row, or half of it. */
    val wide: Boolean = true,
)

/** One thing in a tab's grid. */
sealed interface HomeItem {
    /** What it's known by in the grid, and in a dragged order. */
    val key: String

    data class App(val app: PermissionManifest) : HomeItem {
        override val key get() = app.id
    }

    data class Widget(val widget: HomeWidget) : HomeItem {
        override val key get() = "widget:${widget.id}"
    }
}

/**
 * A tab's grid: its [apps], as [arrange] left them, and its widgets. A widget goes where it was
 * dragged, ahead of the app that followed it then; one that wasn't dragged yet leads, to be seen.
 */
fun homeItems(tab: String, apps: List<PermissionManifest>, layout: HomeLayout): List<HomeItem> {
    val widgets = layout.widgets.filter { it.tab == tab }.map { HomeItem.Widget(it) }
    val items = apps.map<PermissionManifest, HomeItem> { HomeItem.App(it) }.toMutableList()
    val order = if (layout.sort == HomeSort.MANUAL) layout.order[tab].orEmpty() else emptyList()
    val placed = widgets.filter { it.key in order }.sortedBy { order.indexOf(it.key) }
    placed.forEach { widget ->
        val after = order.drop(order.indexOf(widget.key) + 1)
        val next = after.firstNotNullOfOrNull { key -> items.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
        items.add(next ?: items.size, widget)
    }
    return widgets.filterNot { it in placed } + items
}

/** How the user laid out the home screen; see [arrange] for what it does to a tab. */
@Serializable
data class HomeLayout(
    /** Icons across; 0 fits as many as the screen's width takes. */
    val columns: Int = 0,
    val iconSize: HomeIconSize = HomeIconSize.MEDIUM,
    val showLabels: Boolean = true,
    val sort: HomeSort = HomeSort.DEFAULT,
    /** App ids in the order they were dragged to, by tab, for [HomeSort.MANUAL]. */
    val order: Map<String, List<String>> = emptyMap(),
    /** Apps left off the tabs; search still finds them. */
    val hidden: Set<String> = emptySet(),
    /** Each app's own icon color, as ARGB, by app id. */
    val iconColors: Map<String, Int> = emptyMap(),
    /** When each app last started, in ms, by app id, for [HomeSort.RECENT]. */
    val lastUsed: Map<String, Long> = emptyMap(),
    /** Widgets on the tabs, each with settings of its own. */
    val widgets: List<HomeWidget> = emptyList(),
) {
    companion object {
        /** The column counts offered, with 0 for fitting the screen. */
        val COLUMNS = listOf(0, 3, 4, 5, 6)
    }
}

/**
 * The apps a tab shows, in the order it shows them: without the hidden ones, sorted, and with
 * [pinned] apps first, in the order they were pinned. Apps [HomeSort.MANUAL] doesn't know yet,
 * being newer than the drag, follow the ones it does.
 */
fun arrange(apps: List<PermissionManifest>, tab: String, layout: HomeLayout, pinned: List<String> = emptyList()): List<PermissionManifest> {
    val visible = apps.filterNot { it.id in layout.hidden }
    val byName = compareBy<PermissionManifest> { it.name.lowercase() }
    val sorted = when (layout.sort) {
        HomeSort.DEFAULT -> visible
        HomeSort.NAME -> visible.sortedWith(byName)
        HomeSort.NAME_REVERSED -> visible.sortedWith(byName.reversed())
        HomeSort.RECENT -> visible.sortedWith(compareByDescending<PermissionManifest> { layout.lastUsed[it.id] ?: 0L }.then(byName))
        HomeSort.CATEGORY -> visible.sortedWith(compareBy<PermissionManifest> { it.category.lowercase() }.then(byName))
        HomeSort.MANUAL -> {
            val places = layout.order[tab].orEmpty().withIndex().associate { (i, id) -> id to i }
            visible.sortedBy { places[it.id] ?: Int.MAX_VALUE }
        }
    }
    val pins = pinned.mapNotNull { id -> sorted.firstOrNull { it.id == id } }
    return pins + sorted.filterNot { it.id in pinned }
}

/** Keeps the home screen's layout, and hands the icon colors to whatever draws an icon. */
class HomeLayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("home_layout", Context.MODE_PRIVATE)
    private val _layout = MutableStateFlow(load())
    val layout: StateFlow<HomeLayout> = _layout

    init {
        customIconColors.value = _layout.value.iconColors
    }

    fun setColumns(columns: Int) = edit { it.copy(columns = columns) }

    fun setIconSize(size: HomeIconSize) = edit { it.copy(iconSize = size) }

    fun setShowLabels(show: Boolean) = edit { it.copy(showLabels = show) }

    fun setSort(sort: HomeSort) = edit { it.copy(sort = sort) }

    /** Makes [ids] [tab]'s order, and what the home screen sorts by. */
    fun setOrder(tab: String, ids: List<String>) = edit { it.copy(sort = HomeSort.MANUAL, order = it.order + (tab to ids)) }

    /** Forgets every dragged order, back to the default sort. */
    fun resetOrder() = edit { it.copy(sort = HomeSort.DEFAULT, order = emptyMap()) }

    fun setHidden(appId: String, hidden: Boolean) = edit { it.copy(hidden = if (hidden) it.hidden + appId else it.hidden - appId) }

    /** Sets [appId]'s icon color as ARGB; null goes back to the theme's. */
    fun setIconColor(appId: String, argb: Int?) = edit {
        it.copy(iconColors = if (argb == null) it.iconColors - appId else it.iconColors + (appId to argb))
    }

    fun addWidget(kind: WidgetKind, tab: String, target: WidgetTarget?) = edit {
        it.copy(widgets = it.widgets + HomeWidget(java.util.UUID.randomUUID().toString(), kind, tab, target))
    }

    fun removeWidget(id: String) = edit { layout ->
        val key = HomeItem.Widget(HomeWidget(id, WidgetKind.RUNNING, "")).key
        layout.copy(widgets = layout.widgets.filterNot { it.id == id }, order = layout.order.mapValues { (_, ids) -> ids - key })
    }

    fun setWidgetWide(id: String, wide: Boolean) = edit { layout ->
        layout.copy(widgets = layout.widgets.map { if (it.id == id) it.copy(wide = wide) else it })
    }

    /** Notes [appId] starting now. */
    fun markUsed(appId: String, now: Long = System.currentTimeMillis()) = edit { it.copy(lastUsed = it.lastUsed + (appId to now)) }

    private fun edit(change: (HomeLayout) -> HomeLayout) {
        _layout.update(change)
        customIconColors.value = _layout.value.iconColors
        prefs.edit().putString(KEY, json.encodeToString(HomeLayout.serializer(), _layout.value)).apply()
    }

    private fun load(): HomeLayout = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(HomeLayout.serializer(), it) }.getOrNull() }
        ?: HomeLayout()

    private companion object {
        const val KEY = "layout"
        val json = Json { ignoreUnknownKeys = true }
    }
}
