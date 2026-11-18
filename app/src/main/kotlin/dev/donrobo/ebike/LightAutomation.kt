package dev.donrobo.ebike

import android.content.Context
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.Locations
import dev.donrobo.ebike.light.LightDecider
import dev.donrobo.ebike.light.LightInput
import dev.donrobo.ebike.light.LightMode
import dev.donrobo.ebike.light.LightSettings
import dev.donrobo.ebike.light.LightStatus
import dev.donrobo.ebike.light.ManualLight
import dev.donrobo.ebike.light.Precipitation
import dev.donrobo.ebike.light.RainNow
import dev.donrobo.ebike.light.Sun
import dev.donrobo.ebike.light.SunDay
import dev.donrobo.ebike.light.Weather
import dev.donrobo.ebike.light.curveEdges
import dev.donrobo.ebike.light.curveWantsOn
import dev.donrobo.ebike.light.lightReason
import dev.donrobo.ebike.light.rainNow
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Switches the headlight by the connected bike's light settings ([LightDecider]: sun curve both ways, rain,
 * always-on, off); maintains [Repo.light]. Runs until cancelled.
 */
class LightAutomation(private val ctx: Context) {
    private val settings = ctx.settings
    private val decider = LightDecider()
    private val precipitation = MutableStateFlow<Precipitation?>(null)
    private val connected = Repo.live.map { it != null }.distinctUntilChanged()

    suspend fun run() = coroutineScope {
        val wake = Channel<Unit>(Channel.CONFLATED)
        launch { connected.collect { wake.trySend(Unit) } }
        launch { precipitation.collect { wake.trySend(Unit) } }
        // the rider's switch, the lock state and settings changes take effect at once, not after 30 s
        launch { Repo.live.map { it?.report?.lightOn to it?.report?.unlocked }.distinctUntilChanged().collect { wake.trySend(Unit) } }
        launch { settings.anyChange().collect { wake.trySend(Unit) } }
        launch { pollRain() }
        while (true) {
            evaluate()
            withTimeoutOrNull(EVAL_INTERVAL_MS) { wake.receive() }
        }
    }

    private var loggedKind: String? = null
    private var day: Pair<String, SunDay>? = null

    /** Today's curve, recomputed when the date or the (rounded) position changes. */
    private fun sunDay(pos: GeoPoint, date: LocalDate, zone: ZoneId): SunDay {
        val key = String.format(Locale.ROOT, "%s %s %.2f %.2f", date, zone.id, pos.lat, pos.lon)
        day?.takeIf { it.first == key }?.let { return it.second }
        return SunDay.of(date, zone, pos.lat, pos.lon).also { day = key to it }
    }

    private suspend fun evaluate() {
        val nowMs = System.currentTimeMillis()
        val pos = position()
        val elevation = pos?.let { Sun.elevationDeg(nowMs, it.lat, it.lon) }
        val live = Repo.live.value
        // The connected bike's settings (A2: it may not be the one shown); between connections the active bike's.
        val bikeId = live?.bikeId ?: ctx.bikes.active()?.id
        // Q4: a display without a lamp switch gets no lamp commands, whatever the stored mode says.
        val mode = bikeId?.takeIf { ManualLight.lampSwitch(ctx) }?.let { settings.get(LightSettings.mode, it) } ?: LightMode.OFF
        val morningDeg = bikeId?.let { settings.get(LightSettings.morningOffDeg, it) } ?: LightSettings.morningOffDeg.default
        val eveningDeg = bikeId?.let { settings.get(LightSettings.eveningOnDeg, it) } ?: LightSettings.eveningOnDeg.default
        val rain = rainNow(
            bikeId?.let { settings.get(LightSettings.rain, it) } ?: false,
            bikeId?.let { settings.get(LightSettings.rainLevel, it) } ?: LightSettings.rainLevel.default,
            precipitation.value,
        )
        val curveOn = pos?.let { curveWantsOn(elevation!!, Sun.elevationDeg(nowMs + 60_000, it.lat, it.lon), morningDeg, eveningDeg) }
        val report = live?.report
        val input = LightInput(
            connected = report != null,
            unlocked = report?.unlocked ?: false,
            lightOn = report?.lightOn == true,
            mode = mode,
            curveOn = curveOn,
            rainOn = when (rain) { RainNow.WET -> true; RainNow.DRY, RainNow.BELOW_LEVEL, RainNow.DISABLED -> false; RainNow.UNKNOWN -> null },
            nowMs = nowMs,
        )

        var outcome = ""
        decider.update(input)?.let { on ->
            val ok = try {
                checkNotNull(Repo.bike) { "not connected" }.setLight(on)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Repo.log("auto light: switching ${if (on) "on" else "off"} failed: ${e.message}")
                false
            }
            decider.result(ok)
            outcome = if (ok) " → switched ${if (on) "on" else "off"}" else " → switching failed"
        }

        val reason = lightReason(input, decider)
        val now = ZonedDateTime.now()
        val schedule = pos?.let { sunDay(it, now.toLocalDate(), now.zone).schedule(morningDeg, eveningDeg) }
        val (since, until) = curveEdges(reason, schedule, SunDay.minuteOf(now))

        val sun = elevation?.let { String.format(Locale.US, "sun %.1f°", it) } ?: "no position"
        val log = "$reason ($mode, $sun, rain ${rain.name.lowercase()})$outcome"
        val kind = log.replace(Regex("""-?\d+\.\d°"""), "")
        if (input.connected && kind != loggedKind) { loggedKind = kind; Repo.log("auto light: $log") }
        if (!input.connected) loggedKind = null
        Repo.publishLight(LightStatus(reason, rain, since, until))
    }

    /**
     * Whether the weather is needed: a bike is connected and its rain rule can act (mode AUTO, rain switched on).
     * Otherwise the light sends nothing to Open-Meteo (data minimisation); the ride log's two temperature
     * requests per ride ([dev.donrobo.ebike.ride.RideTemperatures]) are separate and share [Weather]'s cache.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val wantsWeather = Repo.live.map { it?.bikeId }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(false)
        else combine(settings.flow(LightSettings.mode, id), settings.flow(LightSettings.rain, id)) { mode, rain ->
            mode == LightMode.AUTO && rain
        }
    }.distinctUntilChanged()

    /**
     * Polls Open-Meteo at connect and every 10 min while [wantsWeather]; null = unknown. Only with a location
     * permission granted right now, so a revoked permission never sends the persisted last fix.
     */
    private suspend fun pollRain() {
        wantsWeather.collectLatest { wanted ->
            precipitation.value = null
            if (!wanted) return@collectLatest
            var failing = false
            while (true) {
                val perms = Locations.permissions(ctx)
                val pos = if (perms.coarse || perms.fine) position() else null
                if (pos == null) { delay(EVAL_INTERVAL_MS); continue }
                precipitation.value = try {
                    Weather.current(pos.lat, pos.lon)?.precipitation.also { failing = false }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!failing) Repo.log("auto light: weather fetch failed: ${e.message ?: e.javaClass.simpleName}")
                    failing = true
                    // keep the last value: the decider holds it anyway, and the status should not flip to "unknown"
                    precipitation.value
                }
                delay(RAIN_INTERVAL_MS)
            }
        }
    }

    /** Newest known position (live fix, system last known, or the persisted one). */
    private fun position(): GeoPoint? = Locations.best(ctx)?.point

    private companion object {
        const val EVAL_INTERVAL_MS = 30_000L
        const val RAIN_INTERVAL_MS = 10 * 60_000L
    }
}
