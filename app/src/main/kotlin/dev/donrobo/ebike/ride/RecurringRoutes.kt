package dev.donrobo.ebike.ride

import android.content.Context
import dev.donrobo.ebike.settings.Bikes
import dev.donrobo.ebike.settings.Scope
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

object RouteSettings {
    /** The bike's named routes (F5). Unnamed recurring routes are not stored: they are recognised anew each time. */
    val routes = Setting.mapped("rides.routes", emptyList(), Scope.PER_BIKE, RouteDef::decodeAll, RouteDef::encodeAll)
}

/** Recurring routes of a bike for the screens: rides grouped by [RouteMatcher], recomputed off the main thread. */
object RecurringRoutes {
    fun groups(ctx: Context, bikeId: String): Flow<RouteGroups> =
        combine(RideRepository.summaries(ctx, bikeId), ctx.settings.flow(RouteSettings.routes, bikeId)) { rides, defs ->
            RouteMatcher.group(rides, defs)
        }.flowOn(Dispatchers.Default)

    /**
     * Names [route] (or renames it). A blank name removes the name of a named route; it is then recognised
     * again as an unnamed one if it still has enough rides.
     */
    fun rename(ctx: Context, bikeId: String, route: RecurringRoute, name: String) {
        val s = ctx.settings
        val defs = s.get(RouteSettings.routes, bikeId)
        val trimmed = name.trim()
        val updated = when {
            route.def != null && trimmed.isEmpty() -> defs.filter { it.id != route.def.id }
            route.def != null -> defs.map { if (it.id == route.def.id) it.copy(name = trimmed) else it }
            trimmed.isEmpty() -> defs
            else -> defs + RouteDef(Bikes.newId(), trimmed, route.seedShape ?: return)
        }
        s.set(RouteSettings.routes, bikeId, updated)
    }
}
