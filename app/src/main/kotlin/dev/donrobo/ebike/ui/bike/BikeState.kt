package dev.donrobo.ebike.ui.bike

import dev.donrobo.ebike.bike.DisplayInfo
import dev.donrobo.ebike.errors.ErrorEntry
import dev.donrobo.ebike.errors.ErrorLogic
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationPermissions
import dev.donrobo.ebike.location.ParkedPosition
import dev.donrobo.ebike.maintenance.MaintenanceStatus
import dev.donrobo.ebike.proto.hex
import dev.donrobo.ebike.settings.Bike

/** W3: what "Hier steht dein Rad" can truthfully say. */
sealed interface ParkedUi {
    /** The bike is connected right now: there is no "last switched off" position to show. */
    data object ConnectedNow : ParkedUi
    /** No connection to this bike has ended yet. */
    data object Never : ParkedUi
    /** The connection ended at [atMs], but no position was known then; [reason] says what to do about it. */
    data class NoPosition(val atMs: Long, val reason: NoPositionReason) : ParkedUi
    /**
     * Position recorded when the connection ended at [atMs]. [staleFixAtMs] is set when the position is clearly
     * older than the switch-off (taken while the app was last open); [accuracyM] when it is coarse.
     */
    data class Known(val atMs: Long, val point: GeoPoint, val staleFixAtMs: Long?, val accuracyM: Int?) : ParkedUi
}

enum class NoPositionReason {
    /** No location permission at all. */
    NO_PERMISSION,
    /** Only "while in use": Android gives no position when the bike is switched off with the phone in the pocket. */
    NEEDS_BACKGROUND,
    /** Permission is there, but no position arrived (system location off, indoors, ...). */
    UNAVAILABLE,
}

/** One line of the error history (W2), or the current error. */
data class ErrorRow(
    /** Code as printed on the display, null if the byte does not decode with the current rules. */
    val code: Int?,
    /** Raw byte(s) as hex, shown when [code] is null. */
    val rawHex: String,
    val known: Boolean,
    /** LIVE: [atMs] is when it occurred. HISTORY: from the display's register, [atMs] only when the app first saw it. */
    val source: ErrorEntry.Source,
    val atMs: Long?,
)

enum class ErrorHealth {
    /** No error now (or, while off, at the end of the last connection). */
    OK,
    /** The drive reports an error now / did at the end of the last connection ([ErrorSection.current]). */
    ERROR,
    /** Never connected: nothing is known. */
    UNKNOWN,
}

data class ErrorSection(
    val health: ErrorHealth,
    /** The live error; [currentIsLive] false = as of the last connection. */
    val current: ErrorRow?,
    val currentIsLive: Boolean,
    /** Newest first, without the occurrence shown as [current]. */
    val history: List<ErrorRow>,
)

/** One maintenance line: status plus whether "Erledigt" is offered right on the card. */
data class MaintenanceRow(val status: MaintenanceStatus) {
    val showDone: Boolean get() = status.level != dev.donrobo.ebike.maintenance.DueLevel.OK
}

/** Everything the Rad tab shows; built by [bikeUiState], rendered by [BikeContent]. */
data class BikeUiState(
    val bike: Bike?,
    val bikes: List<Bike>,
    /** Live or last known odometer; null = never connected. */
    val odoKm: Double?,
    val parked: ParkedUi,
    val maintenance: List<MaintenanceRow>,
    /** null = the display reports no error-code support: the section is hidden (Q4). */
    val errors: ErrorSection?,
    val chargeRemind: Boolean,
    val chargeThresholdPct: Int,
    /** 0 = not set. */
    val batteryWh: Int,
    /** From the last connection; null = never read. */
    val display: DisplayInfo?,
    /** The bike has a paired display (otherwise "Display vergessen" makes no sense; pairing is offered instead). */
    val hasDisplay: Boolean,
)

/** The parked position counts as stale when it is older than the switch-off by more than this. */
const val PARKED_STALE_MS = 10 * 60_000L
/** Positions coarser than this show their accuracy. */
const val PARKED_COARSE_M = 150f

/** Pure W3 mapping. */
fun parkedUi(connectedNow: Boolean, parked: ParkedPosition?, permissions: LocationPermissions): ParkedUi {
    if (connectedNow) return ParkedUi.ConnectedNow
    if (parked == null) return ParkedUi.Never
    val fix = parked.fix ?: return ParkedUi.NoPosition(
        parked.atMs,
        when {
            !permissions.any -> NoPositionReason.NO_PERMISSION
            !permissions.background -> NoPositionReason.NEEDS_BACKGROUND
            else -> NoPositionReason.UNAVAILABLE
        },
    )
    val stale = fix.timeMs == 0L || parked.atMs - fix.timeMs > PARKED_STALE_MS
    return ParkedUi.Known(
        parked.atMs, fix.point,
        staleFixAtMs = if (stale) fix.timeMs else null,
        accuracyM = fix.accuracyM?.takeIf { it > PARKED_COARSE_M }?.toInt(),
    )
}

private fun row(e: ErrorEntry): ErrorRow {
    val d = e.error
    return ErrorRow(d.code, e.rawHex, d.known, e.source, e.seenAtMs)
}

/**
 * Pure W2 mapping. [entries] newest first (as [dev.donrobo.ebike.errors.ErrorLogStore] gives them). [currentRaw] is
 * byte 0 of the live report while connected ([live]; [currentSinceMs] from
 * [dev.donrobo.ebike.errors.DriveErrorState]), else of the last known state; null = never connected. The newest LIVE
 * occurrence of the current error is shown as the current one, not again in the history.
 */
fun errorSection(supported: Boolean, entries: List<ErrorEntry>, currentRaw: Int?, live: Boolean, currentSinceMs: Long? = null): ErrorSection? {
    if (!supported) return null
    val health = when {
        currentRaw == null -> ErrorHealth.UNKNOWN
        currentRaw == 0 -> ErrorHealth.OK
        else -> ErrorHealth.ERROR
    }
    var history = entries
    val current = if (health == ErrorHealth.ERROR) {
        val hex = byteArrayOf(currentRaw!!.toByte()).hex("")
        val occurrence = entries.firstOrNull { it.source == ErrorEntry.Source.LIVE && it.rawHex == hex }
        if (occurrence != null) history = entries - occurrence
        val d = ErrorLogic.decode(byteArrayOf(currentRaw.toByte()))
        ErrorRow(d.code, hex, d.known, ErrorEntry.Source.LIVE, if (live) currentSinceMs ?: occurrence?.seenAtMs else occurrence?.seenAtMs)
    } else null
    return ErrorSection(health, current, live, history.map(::row))
}

enum class RelativeDay { TODAY, YESTERDAY, EARLIER }

/** "heute" / "gestern" / a date. */
fun relativeDay(day: java.time.LocalDate, today: java.time.LocalDate): RelativeDay = when (day) {
    today -> RelativeDay.TODAY
    today.minusDays(1) -> RelativeDay.YESTERDAY
    else -> RelativeDay.EARLIER
}

/** Pure assembly of [BikeUiState]. */
fun bikeUiState(
    bike: Bike?,
    bikes: List<Bike>,
    odoKm: Double?,
    parked: ParkedUi,
    maintenance: List<MaintenanceStatus>,
    errors: ErrorSection?,
    chargeRemind: Boolean,
    chargeThresholdPct: Int,
    batteryWh: Int,
    display: DisplayInfo?,
) = BikeUiState(
    bike, bikes, odoKm, parked, maintenance.map(::MaintenanceRow), errors, chargeRemind, chargeThresholdPct, batteryWh,
    display?.takeIf { !it.isEmpty }, hasDisplay = bike?.displayMac != null,
)
