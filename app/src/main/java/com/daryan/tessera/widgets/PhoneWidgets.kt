// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.daryan.tessera.LauncherApplication
import com.daryan.tessera.MainActivity
import com.daryan.tessera.R
import com.daryan.tessera.WidgetKind
import com.daryan.tessera.WidgetTarget
import com.daryan.tessera.container.RunningSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A widget on the phone's home screen. */
@Serializable
data class PhoneWidget(val kind: WidgetKind, val target: WidgetTarget? = null)

/**
 * Tessera's widgets on the phone's home screen. Each one the phone hands us has an id of its own,
 * and its settings are kept by that id, so two App widgets start two different apps, and none
 * of them depends on Tessera's own home screen or its widgets.
 */
object PhoneWidgets {
    private const val PREFS = "phone_widgets"
    private val json = Json { ignoreUnknownKeys = true }

    /** A place for one app on the Running widget; widgets can't make views as they go. */
    private class Slot(val root: Int, val icon: Int, val name: Int)

    private val SLOTS = listOf(
        Slot(R.id.widget_slot_0, R.id.widget_slot_icon_0, R.id.widget_slot_name_0),
        Slot(R.id.widget_slot_1, R.id.widget_slot_icon_1, R.id.widget_slot_name_1),
        Slot(R.id.widget_slot_2, R.id.widget_slot_icon_2, R.id.widget_slot_name_2),
        Slot(R.id.widget_slot_3, R.id.widget_slot_icon_3, R.id.widget_slot_name_3),
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context, id: Int): PhoneWidget? = prefs(context).getString(id.toString(), null)
        ?.let { runCatching { json.decodeFromString(PhoneWidget.serializer(), it) }.getOrNull() }

    fun save(context: Context, id: Int, widget: PhoneWidget) {
        prefs(context).edit().putString(id.toString(), json.encodeToString(PhoneWidget.serializer(), widget)).apply()
    }

    fun forget(context: Context, id: Int) {
        prefs(context).edit().remove(id.toString()).apply()
    }

    fun providerFor(kind: WidgetKind): Class<out TesseraWidget> = when (kind) {
        WidgetKind.APP -> AppHomeWidget::class.java
        WidgetKind.RUNNING -> RunningHomeWidget::class.java
        WidgetKind.STATS -> StatsHomeWidget::class.java
        WidgetKind.COMMAND -> CommandHomeWidget::class.java
    }

    /** Whether the phone's home screen can be asked to add a widget from inside an app. */
    fun canPin(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    /**
     * Asks the home screen to add a [kind] widget with these settings; it asks the user where. The
     * settings arrive with the id the phone gives it ([Pinned]), so it's right from the first draw.
     */
    fun pin(context: Context, kind: WidgetKind, target: WidgetTarget?): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val intent = Intent(context, Pinned::class.java)
            .setData(Uri.parse("tessera-widget://pin/${System.nanoTime()}"))
            .putExtra(EXTRA_WIDGET, json.encodeToString(PhoneWidget.serializer(), PhoneWidget(kind, target)))
        // Mutable: the phone adds the new widget's id to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val done = PendingIntent.getBroadcast(context, 0, intent, flags)
        return AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, providerFor(kind)), null, done)
    }

    /** Redraws every widget of [kinds] on the phone's home screen; nothing to do when there are none. */
    fun refresh(context: Context, vararg kinds: WidgetKind) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        kinds.forEach { kind ->
            manager.getAppWidgetIds(ComponentName(context, providerFor(kind))).forEach { id -> draw(context, manager, id, kind) }
        }
    }

    /** Where an App or Quick command widget's picture is kept, drawn when it was set up. */
    private fun iconFile(context: Context, appId: String) =
        File(context.filesDir, "widget-icons/${appId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.png")

    /** Keeps [icon] for [appId]'s widgets, small: what a widget draws travels through a binder. */
    fun saveIcon(context: Context, appId: String, icon: Bitmap) {
        val file = iconFile(context, appId)
        file.parentFile?.mkdirs()
        val small = Bitmap.createScaledBitmap(icon.copy(Bitmap.Config.ARGB_8888, false), ICON_PX, ICON_PX, true)
        file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun draw(context: Context, manager: AppWidgetManager, id: Int, kind: WidgetKind) {
        val widget = load(context, id) ?: PhoneWidget(kind)
        manager.updateAppWidget(
            id,
            when (widget.kind) {
                WidgetKind.APP, WidgetKind.COMMAND -> tile(context, id, widget)
                WidgetKind.RUNNING -> running(context)
                WidgetKind.STATS -> stats(context, id)
            },
        )
    }

    private fun tile(context: Context, id: Int, widget: PhoneWidget): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tile)
        val target = widget.target
        if (target == null) {
            // Added from the phone's own widget list and not set up, or its setup was cancelled.
            views.setTextViewText(R.id.widget_name, "Tap to choose")
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_launcher)
            views.setOnClickPendingIntent(R.id.widget_root, setup(context, id))
            return views
        }
        views.setTextViewText(R.id.widget_name, target.name)
        val icon = iconFile(context, target.appId).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        if (icon != null) views.setImageViewBitmap(R.id.widget_icon, icon) else views.setImageViewResource(R.id.widget_icon, R.drawable.ic_launcher)
        val start = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LAUNCH_APP)
            .setData(Uri.parse("tessera-widget://start/$id"))
            .putExtra(MainActivity.EXTRA_APP_ID, target.appId)
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, start, PENDING))
        return views
    }

    private fun setup(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, WidgetSetupActivity::class.java)
            .setData(Uri.parse("tessera-widget://setup/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getActivity(context, id, intent, PENDING)
    }

    private fun runningSessions(context: Context): List<RunningSession> =
        (context.applicationContext as LauncherApplication).container.sessions.sessions.value.filter { it.running }

    /** The Running widget's heading: how many, as its icons may not show them all. */
    internal fun runningTitle(count: Int, shown: Int = SLOTS.size): String = when {
        count == 0 -> "Nothing's running"
        count > shown -> "$count running, $shown shown"
        else -> "$count running"
    }

    private fun opensTessera(context: Context, views: RemoteViews) {
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, open, PENDING))
    }

    private fun running(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_running)
        val sessions = runningSessions(context)
        views.setTextViewText(R.id.widget_title, runningTitle(sessions.size))
        views.setViewVisibility(R.id.widget_empty, if (sessions.isEmpty()) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_slots, if (sessions.isEmpty()) View.GONE else View.VISIBLE)
        SLOTS.forEachIndexed { i, slot ->
            val session = sessions.getOrNull(i)
            // INVISIBLE, not GONE: icons keep their width, rather than one stretching across.
            views.setViewVisibility(slot.root, if (session != null) View.VISIBLE else View.INVISIBLE)
            if (session != null) {
                views.setImageViewBitmap(slot.icon, WidgetIcons.badge(context, session.app))
                views.setTextViewText(slot.name, session.app.name)
                // Straight to that app's window, not to the launcher.
                val show = Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SHOW_SESSION)
                    .setData(Uri.parse("tessera-widget://session/${session.session.id}"))
                    .putExtra(MainActivity.EXTRA_SESSION_ID, session.session.id)
                views.setOnClickPendingIntent(slot.root, PendingIntent.getActivity(context, session.session.id.hashCode(), show, PENDING))
            }
        }
        opensTessera(context, views)
        return views
    }

    private fun stats(context: Context, id: Int): RemoteViews {
        val stats = WidgetStats.read(context, runningSessions(context).size)
        val views = RemoteViews(context.packageName, R.layout.widget_stats)
        views.setProgressBar(R.id.widget_memory_ring, 100, stats.memoryPercent, false)
        views.setTextViewText(R.id.widget_memory_percent, "${stats.memoryPercent}%")
        views.setTextViewText(R.id.widget_memory_detail, stats.memoryShort)
        views.setProgressBar(R.id.widget_storage_ring, 100, stats.storagePercent, false)
        views.setTextViewText(R.id.widget_storage_percent, "${stats.storagePercent}%")
        views.setTextViewText(R.id.widget_storage_detail, stats.storageShort)
        views.setTextViewText(R.id.widget_running_count, stats.runningShort)
        // Android only wakes widgets every half hour or so: a tap brings this up to date.
        val again = Intent(context, StatsHomeWidget::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .setData(Uri.parse("tessera-widget://stats/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(context, id, again, PENDING))
        return views
    }

    private const val ICON_PX = 192
    private const val EXTRA_WIDGET = "com.daryan.tessera.widget"
    private const val PENDING = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** The phone added a widget we asked for: its settings come with the id it now has. */
    class Pinned : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val widget = intent.getStringExtra(EXTRA_WIDGET)
                ?.let { runCatching { json.decodeFromString(PhoneWidget.serializer(), it) }.getOrNull() } ?: return
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
            save(context, id, widget)
            draw(context, AppWidgetManager.getInstance(context), id, widget.kind)
        }
    }
}

/** One kind of widget on the phone's home screen; the manifest lists each. */
abstract class TesseraWidget(private val kind: WidgetKind) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { PhoneWidgets.draw(context, manager, it, kind) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { PhoneWidgets.forget(context, it) }
    }
}

class AppHomeWidget : TesseraWidget(WidgetKind.APP)
class RunningHomeWidget : TesseraWidget(WidgetKind.RUNNING)
class StatsHomeWidget : TesseraWidget(WidgetKind.STATS)
class CommandHomeWidget : TesseraWidget(WidgetKind.COMMAND)
