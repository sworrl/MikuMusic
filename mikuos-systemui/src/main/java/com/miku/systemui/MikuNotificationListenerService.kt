package com.miku.systemui

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One posted notification, flattened for the shade. Everything the Pixel-style row needs:
 * app identity, title/text (+ big text when expanded), actions, content intent, progress.
 */
data class MikuNotif(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val appIcon: Drawable?,
    val title: String,
    val text: String,
    val bigText: String?,
    val subText: String?,
    val postTime: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val contentIntent: PendingIntent?,
    val actions: List<Pair<String, PendingIntent?>>,
    val largeIcon: Bitmap?,
    val groupKey: String?,
    val isGroupSummary: Boolean,
    val accent: Int,
    val hasMediaSession: Boolean,
    val progress: Int?,
    val progressMax: Int?,
    val progressIndeterminate: Boolean
)

/** Process-wide store the shade observes; fed by [MikuNotificationListenerService]. */
object MikuNotificationStore {
    private const val TAG = "MikuNotifs"
    private val _items = MutableStateFlow<List<MikuNotif>>(emptyList())
    val items: StateFlow<List<MikuNotif>> = _items
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    @Volatile internal var listener: MikuNotificationListenerService? = null

    internal fun setConnected(on: Boolean) { _connected.value = on; if (!on) _items.value = emptyList() }

    internal fun refresh(ctx: Context, sbns: Array<StatusBarNotification>?) {
        val list = ArrayList<MikuNotif>()
        val pm = ctx.packageManager
        sbns?.forEach { sbn ->
            runCatching { flatten(ctx, pm, sbn) }.getOrNull()?.let { list.add(it) }
        }
        // Group handling: drop a summary when any child of the same group is present.
        val childGroups = list.filter { !it.isGroupSummary && it.groupKey != null }.map { it.groupKey }.toSet()
        val visible = list.filter { !(it.isGroupSummary && it.groupKey in childGroups) }
            .filter { it.pkg != ctx.packageName }
            .sortedWith(compareBy<MikuNotif> { it.isOngoing }.thenByDescending { it.postTime })
        _items.value = visible
    }

    private fun flatten(ctx: Context, pm: android.content.pm.PackageManager, sbn: StatusBarNotification): MikuNotif? {
        val n = sbn.notification ?: return null
        val ex = n.extras
        val appInfo = runCatching { pm.getApplicationInfo(sbn.packageName, 0) }.getOrNull()
        val appLabel = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: sbn.packageName
        val appIcon = appInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: appLabel
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val large: Bitmap? = runCatching {
            val icon = n.getLargeIcon() ?: ex.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON)
            icon?.loadDrawable(ctx)?.toBitmapSafe(96)
        }.getOrNull()
        val actions = n.actions?.map { a -> (a.title?.toString() ?: "") to a.actionIntent } ?: emptyList()
        val hasSession = ex.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            (n.category == Notification.CATEGORY_TRANSPORT)
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val prog = if (max > 0 || ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) ex.getInt(Notification.EXTRA_PROGRESS, 0) else null
        return MikuNotif(
            key = sbn.key, pkg = sbn.packageName, appLabel = appLabel, appIcon = appIcon,
            title = title, text = text, bigText = big?.takeIf { it.isNotBlank() && it != text }, subText = sub,
            postTime = sbn.postTime, isOngoing = sbn.isOngoing, isClearable = sbn.isClearable,
            contentIntent = n.contentIntent, actions = actions, largeIcon = large,
            groupKey = sbn.groupKey, isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            accent = n.color, hasMediaSession = hasSession,
            progress = prog, progressMax = if (max > 0) max else null,
            progressIndeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        )
    }

    fun dismiss(key: String) {
        runCatching { listener?.cancelNotification(key) }.onFailure { Log.w(TAG, "dismiss: $it") }
    }

    fun dismissAll() {
        runCatching { listener?.cancelAllNotifications() }.onFailure { Log.w(TAG, "dismissAll: $it") }
    }

    /** Fire a notification's content/action intent; the shade closes itself afterwards. */
    fun send(ctx: Context, pi: PendingIntent?): Boolean {
        pi ?: return false
        return runCatching {
            val opts = android.app.ActivityOptions.makeBasic()
            runCatching {
                // Android 14: allow the notification's own activity to come to front from our shade.
                val m = opts.javaClass.getMethod("setPendingIntentBackgroundActivityStartMode", Int::class.javaPrimitiveType)
                m.invoke(opts, 1 /* MODE_BACKGROUND_ACTIVITY_START_ALLOWED */)
            }
            pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            true
        }.getOrDefault(false)
    }

    /** Make sure the system knows about our listener (platform-signed → WRITE_SECURE_SETTINGS). */
    fun ensureEnabled(ctx: Context) {
        try {
            val cr = ctx.contentResolver
            val me = ComponentName(ctx, MikuNotificationListenerService::class.java).flattenToString()
            val cur = Settings.Secure.getString(cr, "enabled_notification_listeners") ?: ""
            if (!cur.split(':').contains(me)) {
                Settings.Secure.putString(cr, "enabled_notification_listeners", if (cur.isBlank()) me else "$cur:$me")
                Log.i(TAG, "enabled notification listener")
            }
        } catch (t: Throwable) { Log.w(TAG, "ensureEnabled: $t") }
    }
}

internal fun Drawable.toBitmapSafe(sizePx: Int): Bitmap? = runCatching {
    val w = if (intrinsicWidth > 0) minOf(intrinsicWidth, sizePx) else sizePx
    val h = if (intrinsicHeight > 0) minOf(intrinsicHeight, sizePx) else sizePx
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    setBounds(0, 0, w, h); draw(c); bmp
}.getOrNull()

class MikuNotificationListenerService : NotificationListenerService() {
    private val tag = "MikuNotifs"

    override fun onListenerConnected() {
        super.onListenerConnected()
        MikuNotificationStore.listener = this
        MikuNotificationStore.setConnected(true)
        refresh()
        Log.i(tag, "listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MikuNotificationStore.listener = null
        MikuNotificationStore.setConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { refresh() }
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) { refresh() }

    private fun refresh() {
        val sbns = runCatching { activeNotifications }.getOrNull()
        MikuNotificationStore.refresh(this, sbns)
    }
}
