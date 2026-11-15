package dev.donrobo.ebike.widget

import android.content.Context
import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.counters.CounterInputs
import dev.donrobo.ebike.counters.CounterSettings
import dev.donrobo.ebike.counters.CounterState
import dev.donrobo.ebike.counters.Counters
import dev.donrobo.ebike.counters.DistanceCounter
import dev.donrobo.ebike.counters.decimals
import dev.donrobo.ebike.currentConnectionStatus
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.light.ManualLight
import dev.donrobo.ebike.range.RangeEstimate
import dev.donrobo.ebike.range.RangeState
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.settings.GeneralSettings
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.units.Measure
import dev.donrobo.ebike.units.UnitFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.donrobo.ebike.ui.components.uiLocale

/**
 * What the rider sees without opening the app: home-screen widget (K1) and the connection notification (K3) are
 * rendered from this one value, built by [bikeGlance]. The quick-settings tile (K2) shows the same [LightControl].
 * Values are already rounded to what is shown, so two equal glances render identically.
 */
data class BikeGlance(
    /** The bike's name; null = no bike set up yet. */
    val bikeName: String?,
    val link: ConnectionStatus,
    val batteryPct: Int?,
    /** Remaining range, whole km/mi; null = not known yet (range not learned, see [RangeState]). */
    val range: Measure?,
    val lightOn: Boolean?,
    /** Set when the values are the bike's last known state rather than live: when they were received (U2). */
    val asOfMs: Long?,
    /** The hand light switch: switchable only live, unlocked and with a lamp switch ([LightControl]). */
    val light: LightControl,
    /** Connected, but the bike reports it is locked. */
    val locked: Boolean,
    /** The distance counter the rider chose for the widget, and its value (null = not known yet). */
    val counter: DistanceCounter = DistanceCounter.SINCE_CHARGE,
    val counterValue: Measure? = null,
) {
    val live: Boolean get() = asOfMs == null && batteryPct != null
}

/** Pure assembly for [bike]: its [snapshot] (live only while it is the connected bike, else last known) and [status]. */
fun bikeGlance(
    bike: Bike?,
    status: ConnectionStatus,
    snapshot: Snapshot?,
    range: RangeEstimate?,
    fmt: UnitFormatter,
    lampSwitch: Boolean = true,
    counter: DistanceCounter = DistanceCounter.SINCE_CHARGE,
    counters: CounterInputs = CounterInputs.NONE,
): BikeGlance {
    val live = snapshot?.live == true
    val report = snapshot?.report
    val counterKm = Counters.value(counter, counters.copy(live = live, odoKm = report?.odoKm, tripKm = report?.tripKm)).km
    return BikeGlance(
        bikeName = bike?.name,
        link = status,
        batteryPct = report?.batteryPct,
        range = range?.let { fmt.distance(it.remainingKm, 0) },
        lightOn = report?.lightOn,
        asOfMs = snapshot?.asOfMs,
        light = LightControl.of(snapshot?.liveReport, lampSwitch),
        locked = live && report?.unlocked == false,
        counter = counter,
        counterValue = counterKm?.let { fmt.distance(it, counter.decimals()) },
    )
}

/**
 * The bike a surface shows: the widget the active bike; the connection notification ([forService]) the bike the
 * service is connected to, else the active one (A2: they differ when the rider picks another bike meanwhile).
 */
private fun glanceBike(ctx: Context, forService: Boolean): Bike? =
    Repo.sessionBikeId.value?.takeIf { forService }?.let(ctx.bikes::get) ?: ctx.bikes.active()

/** The current glance, read from [Repo], the settings and [RangeState]. */
fun currentGlance(ctx: Context, forService: Boolean = false): BikeGlance {
    val bike = glanceBike(ctx, forService)
    // Range and counters are computed for the active bike only.
    val active = bike != null && bike.id == ctx.bikes.active()?.id
    return bikeGlance(
        bike, currentConnectionStatus(ctx, bike), bike?.let { Repo.snapshot(ctx.settings, it.id) },
        RangeState.estimate.value.takeIf { active },
        UnitFormatter(ctx.settings[GeneralSettings.units], ctx.uiLocale()), ManualLight.lampSwitch(ctx),
        ctx.settings[CounterSettings.widget], if (active) CounterState.inputs.value else CounterInputs.NONE,
    )
}

/**
 * Every change of the glance. The live report arrives twice a second, but since the glance only holds
 * rounded, shown values, this emits only when something visible changed.
 */
fun glanceFlow(ctx: Context, forService: Boolean = false): Flow<BikeGlance> =
    combine(
        combine(Repo.conn, Repo.running, Repo.linkLost, Repo.sessionBikeId) { _, _, _, _ -> },
        Repo.live, RangeState.estimate, ctx.settings.anyChange(), CounterState.inputs,
    ) { _, _, _, _, _ -> currentGlance(ctx, forService) }
        .distinctUntilChanged()
