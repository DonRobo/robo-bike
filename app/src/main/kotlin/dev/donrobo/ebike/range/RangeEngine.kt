package dev.donrobo.ebike.range

import android.content.Context
import dev.donrobo.ebike.ConnState
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.battery.LiveCharge
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.location.LocationSettings
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.notify.Alert
import dev.donrobo.ebike.notify.AlertKind
import dev.donrobo.ebike.notify.Alerts
import dev.donrobo.ebike.ride.RideRepository
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

/** Settings of "Komme ich heim?" read together. */
data class HomeSettings(val enabled: Boolean, val home: GeoPoint?, val reserveKm: Double)

/**
 * Single writer of [RangeState]. Runs for the whole process (started by the Application), so Start, the
 * handlebar mode and the widget have a range whether or not the service runs (then from the last known
 * battery, U2).
 *
 * The learned part ([RangeModel.learn]) is recomputed only when the ride list or the window changes; while
 * riding, the ride list refreshes every 30 s but the ride being recorded is left out, so the result stays
 * the same and `distinctUntilChanged` stops it. Live reports (every 0.5 s) feed the charge estimate
 * ([LiveCharge]) and only pass on when the estimated battery or the assist level change; then only the cheap
 * [compose] runs.
 */
object RangeEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            try { run(app) } catch (e: CancellationException) { throw e } catch (e: Exception) { Repo.log("RangeEngine crashed: $e") }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run(ctx: Context) {
        val settings = ctx.settings
        ctx.bikes.activeFlow().collectLatest { bike ->
            if (bike == null) { publish(null); return@collectLatest }
            val id = bike.id
            val zone = ZoneId.systemDefault()
            // The link belongs to one bike at a time (A2): recording, live readings and the live position only
            // count for the bike they come from, not for whichever bike is shown.
            val linkedHere = Repo.linkedTo(id)

            // while recording, the newest file is the ride in progress: learn only from finished rides
            val finished = combine(RideRepository.summaries(ctx, id), Repo.recordingFor(id)) { rides, recording ->
                if (recording && rides.isNotEmpty()) rides.drop(1) else rides
            }.distinctUntilChanged()

            val learned: Flow<Pair<LearnedRange, Int>> = combine(
                finished, settings.flow(RangeSettings.window, id),
                minuteTicks().map { YearMonth.now(zone) }.distinctUntilChanged(),
            ) { rides, window, month ->
                RangeModel.learn(rides, window, month, zone) to window
            }.distinctUntilChanged().flowOn(Dispatchers.Default)

            val detour = combine(
                finished, settings.flow(RangeSettings.window, id), settings.flow(RangeSettings.detourAuto, id), settings.flow(RangeSettings.detourManual, id),
            ) { rides, window, auto, manual ->
                DetourCalc.effective(auto, manual, rides, window)
            }.distinctUntilChanged().flowOn(Dispatchers.Default)

            // live: the estimated charge (start level minus the learned consumption over the distance ridden, held
            // within what the readings allow), not the momentary (sagging) reading; reset per live session
            val charge = LiveCharge()
            // one flow for both, so the model is learned once per change: the estimate needs its rate and curve
            val learnedAndReading = combine(learned, Repo.snapshotFlow(settings, id)) { l, s ->
                val r = s?.report
                l to if (s?.live == true && r != null) {
                    val rate = RangeCalc.rateFor(l.first, r.gear)?.takeIf { it > 0 }
                    charge.update(System.currentTimeMillis(), r.batteryPct, r.odoKm, rate?.let { { p: Int -> it / l.first.factor(p) } })
                        ?.let { RangeReading(it, r.gear, r.gears, live = true) }
                } else {
                    charge.reset()
                    r?.let { RangeReading(it.batteryPct, it.gear, it.gears, live = false) }
                }
            }.distinctUntilChanged()

            val position = combine(Locations.fix, linkedHere, settings.flow(LocationSettings.parked, id), minuteTicks()) { fix, linked, parked, now ->
                HomeCalc.position(linked, fix, parked, now)
            }.distinctUntilChanged()

            val home = combine(
                settings.flow(RangeSettings.homeEnabled), settings.flow(RangeSettings.home), settings.flow(RangeSettings.reserveKm), ::HomeSettings,
            )

            combine(
                learnedAndReading, combine(settings.flow(RangeSettings.marginPct, id), detour, ::Pair),
                combine(position, Locations.status) { p, s -> p to s.limit }, home,
            ) { lr, md, pos, h ->
                val (l, r) = lr
                compose(l.first, l.second, r, md.first, pos.first, pos.second, h, md.second)
            }.distinctUntilChanged().collect { d ->
                publish(d)
                if (d.reading?.live == true) reportHomeWarning(ctx, id, d.home) // R5: only while riding
            }
        }
    }

    /** Pure assembly of everything the range feature shows (unit-tested). */
    fun compose(
        learned: LearnedRange, window: Int, reading: RangeReading?, marginPct: Int,
        position: BikePosition?, limit: LocationLimit?, home: HomeSettings, detour: Detour = Detour(DetourCalc.DEFAULT, auto = true),
    ): RangeDetail {
        val estimate = RangeCalc.estimate(learned, reading, marginPct)
        val full = RangeCalc.full(learned, reading?.level, marginPct)
        val homePoint = home.home?.takeIf { home.enabled }
        return RangeDetail(
            learned = learned, reading = reading, marginPct = marginPct, window = window, full = full, estimate = estimate,
            levels = RangeCalc.levels(learned, reading?.batteryPct ?: 100, reading?.levelCount, marginPct),
            months = RangeCalc.months(learned, marginPct),
            position = position,
            home = HomeCalc.status(home.enabled, home.home, position, limit, estimate, home.reserveKm, detour.factor, learned = full != null),
            detour = detour,
            area = if (position != null && estimate != null && homePoint != null) RangeCalc.area(position.point, homePoint, estimate.remainingKm, home.reserveKm, detour.factor) else null,
        )
    }

    private fun publish(d: RangeDetail?) {
        RangeState.detail.value = d
        RangeState.estimate.value = d?.estimate
        RangeState.full.value = d?.full
        RangeState.home.value = d?.home?.let(HomeCalc::reach)
    }

    /** R5: reports the condition to [Alerts], which de-duplicates; called on every change while riding. */
    private fun reportHomeWarning(ctx: Context, bikeId: String, status: HomeStatus) {
        when (HomeWarning.condition(status)) {
            HomeWarning.Condition.HOLDS -> (status as HomeStatus.Known).let {
                Alerts.show(ctx, bikeId, Alert.HomeRange(remainingKm = it.remainingKm, homeKm = it.distanceKm))
            }
            HomeWarning.Condition.CLEAR -> Alerts.resolve(ctx, bikeId, AlertKind.HOME_RANGE)
            HomeWarning.Condition.UNKNOWN -> {}
        }
    }

    /** Wall clock every minute: fix age and the current month move on without other changes. */
    private fun minuteTicks(): Flow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(60_000) }
    }
}
