package dev.donrobo.ebike.notify

import android.content.Context
import dev.donrobo.ebike.R
import dev.donrobo.ebike.errors.DriveErrorEvent
import dev.donrobo.ebike.errors.DriveErrorListener
import dev.donrobo.ebike.proto.ErrorCodes
import dev.donrobo.ebike.settings.bikes

/**
 * The drive-error alert ([Alerts], kind DRIVE_ERROR): shown once per episode of
 * [dev.donrobo.ebike.errors.DriveErrorWatcher] with the plain-language title and advice, resolved when it ends.
 */
class DriveErrorAlerts(ctx: Context) : DriveErrorListener {
    private val ctx = ctx.applicationContext

    init {
        // An episode left open by a killed process is over: what the display reports now decides.
        this.ctx.bikes.active()?.let { Alerts.resolve(this.ctx, it.id, AlertKind.DRIVE_ERROR) }
    }

    override fun onEvent(e: DriveErrorEvent, nowMs: Long) {
        when (e) {
            is DriveErrorEvent.Appeared -> Alerts.show(ctx, e.bikeId, Alert.DriveError(e.error.raw, e.error.code))
            is DriveErrorEvent.Ended -> Alerts.resolve(ctx, e.bikeId, AlertKind.DRIVE_ERROR)
        }
    }
}

/** Title and advice of a drive error, from res/values/strings_errors.xml. */
data class DriveErrorText(val title: String, val advice: String)

/** Plain-language text for [code]; codes outside the Bafang table (or undecodable bytes) get the generic text. */
fun driveErrorText(ctx: Context, code: Int?): DriveErrorText {
    val key = code?.takeIf { it in ErrorCodes.KNOWN }?.let(ErrorCodes::resourceKey)
    val r = ctx.resources
    @Suppress("DiscouragedApi") // the table is keyed by code; the keys are fixed and covered by a test
    fun id(suffix: String) = key?.let { r.getIdentifier("${it}_$suffix", "string", ctx.packageName) }?.takeIf { it != 0 }
    return DriveErrorText(
        ctx.getString(id("title") ?: R.string.error_unknown_title),
        ctx.getString(id("advice") ?: R.string.error_unknown_advice),
    )
}

/** "Fehler 21" (as printed on the display), or the raw byte if it does not decode. */
fun driveErrorLabel(ctx: Context, raw: Int, code: Int?): String =
    ctx.getString(R.string.notif_error_code, code?.let { "%02d".format(it) } ?: "0x%02X".format(raw))
