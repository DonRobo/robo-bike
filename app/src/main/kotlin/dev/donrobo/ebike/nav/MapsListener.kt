package dev.donrobo.ebike.nav

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon as GIcon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.BundleCompat
import dev.donrobo.ebike.NavDebug
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.proto.Icon

/**
 * Reads Google Maps' ongoing turn-by-turn notification into [Repo.mapsNav].
 * The notification layout is not documented; everything seen is dumped into [Repo.navDebug].
 */
class MapsListener : NotificationListenerService() {
    private var navKey: String? = null

    override fun onListenerConnected() {
        val nav = activeNotifications.orEmpty().filter(::isNav)
        (nav.firstOrNull { it.notification.category == Notification.CATEGORY_NAVIGATION } ?: nav.firstOrNull())
            ?.let(::handle) ?: clear("no Maps navigation on connect")
    }

    override fun onListenerDisconnected() = clear("listener disconnected")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS) return
        when {
            isNav(sbn) -> handle(sbn)
            sbn.key == navKey -> clear("Maps notification no longer ongoing")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == navKey) clear("Maps notification removed")
    }

    private fun isNav(sbn: StatusBarNotification) = sbn.packageName == MAPS &&
        (sbn.isOngoing || sbn.notification.category == Notification.CATEGORY_NAVIGATION)

    private fun clear(why: String) {
        navKey = null
        if (Repo.mapsNav.value != null) Repo.log("Maps nav ended ($why)")
        Repo.publishMapsNav(null)
        Repo.navDebug.value?.let { Repo.publishNavDebug(NavDebug(it.texts + "-- $why", it.icon, null, it.summary + "-- $why", it.shapeIcon, it.textIcon)) }
    }

    private var lastTitle = ""

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification
        val ex = n.extras
        val dbg = mutableListOf(
            "key: ${sbn.key}", "category: ${n.category}, ongoing: ${sbn.isOngoing}",
            "extras keys: ${ex.keySet().sorted().joinToString()}",
        )
        val extraTexts = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT,
        ).mapNotNull { k -> ex.getCharSequence(k)?.toString()?.takeIf { it.isNotBlank() }?.also { dbg += "extra $k: $it" } }
        for (k in listOf(Notification.EXTRA_TITLE_BIG, Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_INFO_TEXT))
            ex.getCharSequence(k)?.let { dbg += "extra $k: $it (unused)" }

        var texts = MapsExtract.parseTexts(extraTexts)
        var arrow = largeIcon(n, ex)
        dbg += "large icon: ${arrow?.let { "${it.width}x${it.height}" }}"
        if (texts.distM == null && !texts.arrival) {
            dbg += "remoteViews fallback: used"
            val (viewTexts, images) = walkRemoteViews(n, dbg)
            viewTexts.forEachIndexed { i, t -> dbg += "view text[$i]: $t" }
            texts = MapsExtract.parseTexts(viewTexts)
            if (arrow == null) arrow = images.maxByOrNull { it.width * it.height }
        } else dbg += "remoteViews fallback: not needed"

        // e.g. "Auf Lindenweg nach Nordwesten" at the start of a route: an instruction without a distance
        if (texts.distM == null && !texts.arrival && n.category == Notification.CATEGORY_NAVIGATION) {
            if (MapsExtract.isInstructionWithoutDistance(extraTexts.firstOrNull())) {
                dbg += "navigation instruction without a distance: sending 0 m"
                texts = texts.copy(distM = 0)
            } else dbg += "no maneuver in the title: not sent"
        }
        val shapeIcon = arrow?.let(::classify) ?: Icon.UNKNOWN
        dbg += "classifier (arrow shape): $shapeIcon, from text: ${texts.textIcon}"
        // Android 16 progress-style notification: progress/progressMax are metres along the route
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (texts.remainingM == null && max > 0) {
            val left = (max - ex.getInt(Notification.EXTRA_PROGRESS, 0)).coerceAtLeast(0).toLong()
            dbg += "remaining from progress: $left m"
            texts = texts.copy(remainingM = left)
        }
        val state = MapsExtract.navState(texts, texts.textIcon ?: shapeIcon)
        dbg += "parsed: $state"

        val prev = Repo.mapsNav.value
        if (state == null) {
            if (prev != null) clear("navigation notification without a distance")
        } else {
            navKey = sbn.key
            val title = extraTexts.firstOrNull() ?: ""
            if (prev == null) Repo.log("Maps nav started: $state \"$title\"")
            else if (prev.icon != state.icon || title.substringAfter('·') != lastTitle.substringAfter('·'))
                Repo.log("Maps nav maneuver: ${state.icon} in ${state.distM} m \"$title\"")
            lastTitle = title
            Repo.publishMapsNav(state)
        }
        val summary = listOf(
            "category: ${n.category}, ongoing: ${sbn.isOngoing}",
            "title: ${extraTexts.firstOrNull() ?: "-"}",
            "classifier (arrow shape): $shapeIcon",
        )
        Repo.publishNavDebug(NavDebug(dbg, arrow, state, summary, shapeIcon = arrow?.let { shapeIcon }, textIcon = texts.textIcon))
    }

    private fun largeIcon(n: Notification, ex: Bundle): Bitmap? = runCatching {
        n.getLargeIcon()?.loadDrawable(this)?.let(::toBitmap)
            ?: when (val extra = largeIconExtra(ex)) {
                is Bitmap -> extra
                is GIcon -> extra.loadDrawable(this)?.let(::toBitmap)
                else -> null
            }
    }.getOrNull()

    /** Inflates the notification's RemoteViews and collects TextView texts (tree order) and ImageView bitmaps. */
    private fun walkRemoteViews(n: Notification, dbg: MutableList<String>): Pair<List<String>, List<Bitmap>> {
        val texts = mutableListOf<String>()
        val images = mutableListOf<Bitmap>()
        @Suppress("DEPRECATION")
        val rv = n.bigContentView ?: n.contentView
            ?: runCatching { Notification.Builder.recoverBuilder(this, n).createBigContentView() }.getOrNull()
        dbg += "remoteViews: ${if (rv == null) "none" else rv.`package` + "/" + rv.layoutId}"
        val root = try { rv?.apply(this, null) } catch (e: Exception) { dbg += "remoteViews apply failed: $e"; null }
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            val id = idName(v)
            when (v) {
                is TextView -> v.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
                is ImageView -> v.drawable?.let(::toBitmap)?.let { b ->
                    val skip = id in APP_ICON_IDS || b.width < 16 || b.height < 16
                    dbg += "view image $id ${b.width}x${b.height}${if (skip) " (skipped)" else ""}"
                    if (!skip) images += b
                }
                is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        root?.let(::walk)
        return texts to images
    }

    private fun idName(v: View) =
        if (v.id == View.NO_ID) "-" else runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("#${v.id}")

    private fun toBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            d.setBounds(0, 0, w, h); d.draw(Canvas(b))
        }
    }

    private fun classify(b: Bitmap): Icon {
        val s = Bitmap.createScaledBitmap(b.copy(Bitmap.Config.ARGB_8888, false), N, N, true)
        val px = IntArray(N * N).also { s.getPixels(it, 0, N, 0, 0, N, N) }
        return ArrowClassifier.classify(MapsExtract.mask(px), N, N)
    }

    private companion object {
        const val MAPS = "com.google.android.apps.maps"
        const val N = 48
        /** Small app icon / badge views of the standard notification templates. */
        val APP_ICON_IDS = setOf("icon", "app_icon", "profile_badge", "conversation_icon_badge_icon")
    }
}

/**
 * The large icon a notification carries in its extras: a [Bitmap] (older apps) or a [GIcon], else null.
 * Goes through [BundleCompat] because the typed `Bundle.getParcelable(key, Class)` only exists from API 33.
 */
@Suppress("DEPRECATION") // EXTRA_LARGE_ICON: the fallback after Notification.getLargeIcon()
internal fun largeIconExtra(ex: Bundle): Any? =
    BundleCompat.getParcelable(ex, Notification.EXTRA_LARGE_ICON, Bitmap::class.java)
        ?: BundleCompat.getParcelable(ex, Notification.EXTRA_LARGE_ICON, GIcon::class.java)
