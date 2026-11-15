package dev.donrobo.ebike.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.donrobo.ebike.light.ManualLight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Taps on the widget's light button and the notification's "Licht an/aus". Carries the state to switch to
 * (what the button showed), so a tap on a slightly stale surface still does what the rider saw.
 * Only works while the service holds the connection in this process; otherwise [ManualLight] refuses.
 */
class SurfaceActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_SET_LIGHT || !intent.hasExtra(EXTRA_ON)) return
        val on = intent.getBooleanExtra(EXTRA_ON, false)
        val from = intent.getStringExtra(EXTRA_FROM) ?: "surface"
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { withTimeoutOrNull(TIMEOUT_MS) { ManualLight.set(ctx, on, from) } } finally { pending.finish() }
        }
    }

    companion object {
        private const val ACTION_SET_LIGHT = "dev.donrobo.ebike.SET_LIGHT"
        private const val EXTRA_ON = "on"
        private const val EXTRA_FROM = "from"
        /** Well inside the 10 s a goAsync receiver may take. */
        private const val TIMEOUT_MS = 8_000L

        fun setLight(ctx: Context, on: Boolean, requestCode: Int, from: String = "notification"): PendingIntent = PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, SurfaceActionReceiver::class.java).setAction(ACTION_SET_LIGHT).putExtra(EXTRA_ON, on).putExtra(EXTRA_FROM, from),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
