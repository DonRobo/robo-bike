package dev.donrobo.ebike.counters

import android.content.Context
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.ride.RideRecorder
import dev.donrobo.ebike.ride.RideRepository
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The active bike's [CounterInputs] for Start, the handlebar mode and the widget. Single writer: [CounterEngine]. */
object CounterState {
    internal val mutable = MutableStateFlow(CounterInputs.NONE)
    val inputs: StateFlow<CounterInputs> = mutable.asStateFlow()

    fun value(c: DistanceCounter): CounterReading = Counters.value(c, inputs.value)
}

/**
 * Single writer of [CounterState]; runs for the whole process (started by the Application), like the range
 * engine, so the counters are there with or without the service. Holds no copies: everything is re-derived
 * from the live report / last known state, the ride recorder, the ride log and the stored event odometers.
 */
object CounterEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            try { run(app) } catch (e: CancellationException) { throw e } catch (e: Exception) { Repo.log("CounterEngine crashed: $e") }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run(ctx: Context) {
        val settings = ctx.settings
        ctx.bikes.activeFlow().map { it?.id }.distinctUntilChanged().collectLatest { id ->
            if (id == null) { CounterState.mutable.value = CounterInputs.NONE; return@collectLatest }
            // Only odometer and trip of the report matter: speed changes do not pass distinctUntilChanged below.
            val distances = Repo.snapshotFlow(settings, id).map { s -> Triple(s?.live == true, s?.report?.odoKm, s?.report?.tripKm) }
                .distinctUntilChanged()
            val events = combine(settings.flow(CounterSettings.fullChargeOdo, id), settings.flow(CounterSettings.homeDepartureOdo, id)) { a, b -> a to b }
            val lastRide = RideRepository.summaries(ctx, id).map { it.firstOrNull()?.distanceKm }.distinctUntilChanged()
            combine(distances, RideRecorder.open, lastRide, events) { (live, odo, trip), open, last, (charge, home) ->
                CounterInputs(live, odo, trip, open?.takeIf { it.bikeId == id }, last, charge, home)
            }.distinctUntilChanged().collect { CounterState.mutable.value = it }
        }
    }
}
