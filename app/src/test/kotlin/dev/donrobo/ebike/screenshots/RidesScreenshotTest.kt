package dev.donrobo.ebike.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import dev.donrobo.ebike.backup.Backup
import dev.donrobo.ebike.backup.DataOverview
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationLimit
import dev.donrobo.ebike.ride.RecurringRoute
import dev.donrobo.ebike.ride.RideShape
import dev.donrobo.ebike.ride.RideSummary
import dev.donrobo.ebike.ride.RouteDef
import dev.donrobo.ebike.ride.RouteGroups
import dev.donrobo.ebike.ride.StatsPeriod
import dev.donrobo.ebike.ui.PreviewNavigator
import dev.donrobo.ebike.ui.Tab
import dev.donrobo.ebike.ui.backup.BackupActions
import dev.donrobo.ebike.ui.backup.BackupContent
import dev.donrobo.ebike.ui.backup.BackupMessage
import dev.donrobo.ebike.ui.backup.BackupUiState
import dev.donrobo.ebike.ui.components.chart.ChartPoint
import dev.donrobo.ebike.ui.components.map.HeatLines
import dev.donrobo.ebike.ui.rides.RideActions
import dev.donrobo.ebike.ui.rides.RideChart
import dev.donrobo.ebike.ui.rides.RideCharts
import dev.donrobo.ebike.ui.rides.RideDetailContent
import dev.donrobo.ebike.ui.rides.RideDetailUiState
import dev.donrobo.ebike.ui.rides.RidesActions
import dev.donrobo.ebike.ui.rides.RidesContent
import dev.donrobo.ebike.ui.rides.RouteActions
import dev.donrobo.ebike.ui.rides.RouteContent
import dev.donrobo.ebike.ui.rides.ridesUiState
import dev.donrobo.ebike.ui.stats.StatsActions
import dev.donrobo.ebike.ui.stats.StatsContent
import dev.donrobo.ebike.ui.stats.statsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.cos

/** Fahrten, Fahrtdetail, Statistik, route comparison and backup with the artboards' sample data, plus empty / no-GPS states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RidesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val origin = GeoPoint(48.2085, 16.3721)

    /** Points from artboard pixel coordinates (1 px = 10 m, y down). */
    private fun px(vararg p: Pair<Int, Int>) = p.map { (x, y) ->
        GeoPoint(origin.lat - y * 10 / 111_320.0, origin.lon + x * 10 / (111_320.0 * cos(Math.toRadians(origin.lat))))
    }

    private fun shape(vararg p: Pair<Int, Int>) = RideShape(px(*p))

    private fun ride(
        at: LocalDateTime, km: Double, min: Long, from: Int, to: Int, shape: RideShape? = null,
    ) = RideSummary(
        File("${at.toLocalDate()}_%02d%02d00.csv".format(at.hour, at.minute)), at.atZone(zone).toInstant().toEpochMilli(),
        min * 60, km, from, to, hasTrack = shape != null, shape = shape,
    )

    // the artboard's three rides on Wednesday 11 November 2026
    private val evening = ride(LocalDateTime.of(2026, 11, 11, 21, 53), 10.6, 29, 88, 75, shape(12 to 68, 34 to 64, 32 to 30, 58 to 26, 62 to 12))
    private val afternoon = ride(LocalDateTime.of(2026, 11, 11, 16, 31), 6.4, 21, 92, 86, shape(14 to 18, 28 to 40, 50 to 44, 70 to 66))
    private val morning = ride(LocalDateTime.of(2026, 11, 11, 7, 41), 9.6, 25, 100, 88, shape(62 to 12, 58 to 26, 32 to 30, 34 to 64, 12 to 68))
    private val commuteShape = shape(62 to 12, 58 to 26, 32 to 30, 34 to 64, 12 to 68)
    private val commute = RecurringRoute(
        "n-r1", RouteDef("r1", "Arbeitsweg", commuteShape),
        listOf(
            morning,
            ride(LocalDateTime.of(2026, 11, 10, 7, 38), 9.6, 27, 90, 78, commuteShape),
            ride(LocalDateTime.of(2026, 11, 9, 7, 45), 9.7, 26, 100, 88, commuteShape),
        ),
    )
    private val groups = RouteGroups(listOf(commute), commute.rides.associate { it.id to commute })

    private val ridesActions = object : RidesActions {
        override fun openRide(id: String) {}
        override fun setGpsTrack(on: Boolean) {}
        override fun fixGps(limit: LocationLimit) {}
    }

    @Test @Config(qualifiers = "de-w390dp-h920dp-port-xhdpi")
    fun rides_de() = compose.screenshot("rides_de", Tab.RIDES) {
        RidesContent(ridesUiState(listOf(evening, afternoon, morning), groups, true, null, false, LocalDate.of(2026, 11, 11)), ridesActions, PreviewNavigator)
    }

    @Test @Config(qualifiers = "en-w390dp-h920dp-port-xhdpi")
    fun rides_en() = compose.screenshot("rides_en", Tab.RIDES) {
        RidesContent(ridesUiState(listOf(evening, afternoon, morning), groups, true, null, false, LocalDate.of(2026, 11, 11)), ridesActions, PreviewNavigator)
    }

    /** Fresh install: no rides, GPS track on but only "while in use" location. */
    @Test @Config(qualifiers = "de-w390dp-h920dp-port-xhdpi")
    fun rides_empty_de() = compose.screenshot("rides_empty_de", Tab.RIDES) {
        RidesContent(ridesUiState(emptyList(), RouteGroups.EMPTY, true, LocationLimit.NEEDS_BACKGROUND, false, LocalDate.of(2026, 11, 11)), ridesActions, PreviewNavigator)
    }

    /** Three weeks of commuting before the artboard's day: to work and back on weekdays, a longer tour on Saturdays. */
    private fun weeksOfRides(): List<RideSummary> = listOf(evening, afternoon, morning) + (1..21).flatMap { back ->
        val day = LocalDate.of(2026, 11, 11).minusDays(back.toLong())
        when (day.dayOfWeek) {
            java.time.DayOfWeek.SUNDAY -> emptyList()
            java.time.DayOfWeek.SATURDAY -> listOf(ride(day.atTime(10, 12), 31.3, 82, 100, 75, commuteShape))
            else -> listOf(
                ride(day.atTime(17, 20), 9.7, 28, 88, 77, commuteShape),
                ride(day.atTime(7, 40), 9.6, 26, 100, 88, commuteShape),
            )
        }
    }

    private fun manyRides() = ridesUiState(weeksOfRides(), groups, true, null, false, LocalDate.of(2026, 11, 11))

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun rides_collapsing_de() = compose.scrolled("rides_collapsing_de", Tab.RIDES, Scroll.COLLAPSING) { RidesContent(manyRides(), ridesActions, PreviewNavigator) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun rides_scrolled_de() = compose.scrolled("rides_scrolled_de", Tab.RIDES, Scroll.UNDER) { RidesContent(manyRides(), ridesActions, PreviewNavigator) }

    @Test @Config(qualifiers = "en-$SCROLL_PHONE")
    fun rides_scrolled_en() = compose.scrolled("rides_scrolled_en", Tab.RIDES, Scroll.UNDER) { RidesContent(manyRides(), ridesActions, PreviewNavigator) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun rides_end_de() = compose.scrolled("rides_end_de", Tab.RIDES, Scroll.ToEnd) { RidesContent(manyRides(), ridesActions, PreviewNavigator) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun rides_end_buttons_de() = compose.scrolled("rides_end_buttons_de", Tab.RIDES, Scroll.ToEnd, SystemBars.BUTTONS) {
        RidesContent(manyRides(), ridesActions, PreviewNavigator)
    }

    private val rideActions = object : RideActions {
        override fun back() {}
        override fun expandMap() {}
        override fun compare(routeId: String) {}
        override fun shareGpx() {}
        override fun delete() {}
    }

    /** The artboard's speed curve (pixel path of the Fahrt chart) as km / km/h. */
    private fun speedCurve(): List<ChartPoint> = listOf(
        28 to 120, 36 to 54, 55 to 32, 77 to 39, 87 to 91, 98 to 36, 136 to 28, 163 to 43, 174 to 98, 184 to 39,
        230 to 25, 260 to 36, 276 to 76, 292 to 39, 309 to 69, 314 to 120,
    ).map { (x, y) -> ChartPoint((x - 28) / 286f * 10.6f, (120 - y) / 110f * 30f) }

    private val detailRide = evening.copy(
        shape = RideShape(px(56 to 258, 152 to 262, 144 to 148, 270 to 140, 280 to 58, 340 to 54)),
        endMs = evening.startMs + 29 * 60_000,
    )
    private val charts = RideCharts(
        10.6, speedCurve(),
        listOf(ChartPoint(0f, 88f), ChartPoint(10.6f, 75f)),
        null,
        listOf(ChartPoint(0f, 2f), ChartPoint(3f, 3f), ChartPoint(8f, 2f), ChartPoint(10.6f, 2f)), 3,
        detailRide.shape!!.points,
    )

    @Test @Config(qualifiers = "de-w390dp-h1280dp-port-xhdpi")
    fun ride_de() = compose.screenshot("ride_de") {
        RideDetailContent(RideDetailUiState(detailRide, commute, charts), rideActions)
    }

    @Test @Config(qualifiers = "en-w390dp-h1280dp-port-xhdpi")
    fun ride_en() = compose.screenshot("ride_en") {
        RideDetailContent(RideDetailUiState(detailRide, commute, charts), rideActions)
    }

    /** A ride recorded without GPS: no map, no elevation, no GPX, not on a route. */
    @Test @Config(qualifiers = "de-w390dp-h1100dp-port-xhdpi")
    fun ride_no_gps_de() = compose.screenshot("ride_no_gps_de") {
        val r = afternoon.copy(hasTrack = false, shape = null, endMs = afternoon.startMs + 21 * 60_000)
        RideDetailContent(RideDetailUiState(r, null, charts.copy(distanceKm = 6.4, speed = speedCurve().map { it.copy(x = it.x * 6.4f / 10.6f) }, altitude = null, track = emptyList())), rideActions, initialChart = RideChart.ALTITUDE)
    }

    private val statsActions = object : StatsActions {
        override fun selectPeriod(p: StatsPeriod) {}
        override fun editPrice() {}
        override fun openBike() {}
        override fun openRoute(id: String) {}
        override fun expandHeat() {}
    }

    /** November 2026 like the artboard: rides on the 2nd-6th, 8th and 10th (151 km, 6:52 h, 1.8 charges). */
    private fun novemberRides(): List<RideSummary> {
        val days = listOf(2 to 19.4, 3 to 19.4, 4 to 22.6, 5 to 19.4, 6 to 13.5, 8 to 31.3, 10 to 25.4)
        val minutes = listOf(55L, 55, 62, 54, 38, 82, 66) // 412 min = 6:52
        return days.mapIndexed { i, (d, km) ->
            // 180 % in whole-percent rides = 1.8 charges
            ride(LocalDateTime.of(2026, 11, d, 8, 0), km, minutes[i], 100, 100 - listOf(26, 26, 26, 26, 26, 25, 25)[i], if (i % 2 == 0) commuteShape else null)
        }
    }

    private fun heat(): HeatLines {
        fun dense(pts: List<GeoPoint>) = pts.zipWithNext().flatMap { (a, b) ->
            (0 until 40).map { GeoPoint(a.lat + (b.lat - a.lat) * it / 40, a.lon + (b.lon - a.lon) * it / 40) }
        } + pts.last()
        val main = dense(px(40 to 172, 132 to 168, 126 to 96, 244 to 90, 250 to 34, 310 to 30))
        val a = dense(px(126 to 96, 60 to 100, 52 to 40))
        val b = dense(px(244 to 90, 300 to 86, 306 to 150))
        val c = dense(px(132 to 168, 196 to 172, 194 to 120))
        return HeatLines.build(listOf(main, main, main, main, main, a, a, b, b, c))
    }

    @Test @Config(qualifiers = "de-w390dp-h1220dp-port-xhdpi")
    fun stats_de() = compose.screenshot("stats_de", Tab.RIDES) {
        StatsContent(statsUiState(novemberRides(), groups, StatsPeriod.MONTH, LocalDate.of(2026, 11, 14), 400, 30.0, heat()), statsActions, PreviewNavigator)
    }

    @Test @Config(qualifiers = "en-w390dp-h1220dp-port-xhdpi")
    fun stats_en() = compose.screenshot("stats_en", Tab.RIDES) {
        StatsContent(statsUiState(novemberRides(), groups, StatsPeriod.MONTH, LocalDate.of(2026, 11, 14), 400, 30.0, heat()), statsActions, PreviewNavigator)
    }

    @Test @Config(qualifiers = "de-w390dp-h1220dp-port-xhdpi")
    fun stats_week_de() = compose.screenshot("stats_week_de", Tab.RIDES) {
        StatsContent(statsUiState(novemberRides() + listOf(evening, afternoon, morning), groups, StatsPeriod.WEEK, LocalDate.of(2026, 11, 11), 400, 30.0, heat()), statsActions, PreviewNavigator)
    }

    @Test @Config(qualifiers = "de-w390dp-h1220dp-port-xhdpi")
    fun stats_year_de() = compose.screenshot("stats_year_de", Tab.RIDES) {
        val rides = novemberRides() + (1..10).map { m -> ride(LocalDateTime.of(2026, m, 12, 8, 0), 40.0 + m * 9, 300, 100, 20) }
        StatsContent(statsUiState(rides, groups, StatsPeriod.YEAR, LocalDate.of(2026, 11, 14), 400, 30.0, heat()), statsActions, PreviewNavigator)
    }

    /** Rides without any GPS track and no battery capacity entered: cost, routes and heatmap explain what is missing. */
    @Test @Config(qualifiers = "de-w390dp-h1220dp-port-xhdpi")
    fun stats_no_gps_de() = compose.screenshot("stats_no_gps_de", Tab.RIDES) {
        val rides = novemberRides().map { it.copy(hasTrack = false, shape = null) }
        StatsContent(statsUiState(rides, RouteGroups.EMPTY, StatsPeriod.MONTH, LocalDate.of(2026, 11, 14), 0, 30.0, null), statsActions, PreviewNavigator)
    }

    @Test @Config(qualifiers = "de-w390dp-h1220dp-port-xhdpi")
    fun stats_empty_de() = compose.screenshot("stats_empty_de", Tab.RIDES) {
        StatsContent(statsUiState(emptyList(), RouteGroups.EMPTY, StatsPeriod.MONTH, LocalDate.of(2026, 11, 14), 400, 30.0, null), statsActions, PreviewNavigator)
    }

    @Test @Config(qualifiers = "de-$PHONE")
    fun route_de() = compose.screenshot("route_de") {
        RouteContent(
            commute,
            object : RouteActions {
                override fun back() {}
                override fun rename() {}
                override fun openRide(id: String) {}
            },
        )
    }

    private val backupActions = object : BackupActions {
        override fun back() {}
        override fun export() {}
        override fun pickRestore() {}
    }

    @Test @Config(qualifiers = "de-$PHONE")
    fun backup_de() = compose.screenshot("backup_de") {
        BackupContent(BackupUiState(DataOverview(listOf("Stadtrad"), 42)), backupActions)
    }

    @Test @Config(qualifiers = "en-$PHONE")
    fun backup_error_en() = compose.screenshot("backup_error_en") {
        BackupContent(BackupUiState(DataOverview(listOf("Stadtrad", "Lastenrad"), 1), message = BackupMessage.Invalid(Backup.Problem.FOREIGN)), backupActions)
    }

    // --- scrolled (SCROLL_PHONE, gesture navigation unless named) ---

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun ride_scrolled_de() = compose.scrolled("ride_scrolled_de", scroll = Scroll.UNDER) { RideDetailContent(RideDetailUiState(detailRide, commute, charts), rideActions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun ride_end_de() = compose.scrolled("ride_end_de", scroll = Scroll.ToEnd) { RideDetailContent(RideDetailUiState(detailRide, commute, charts), rideActions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun ride_end_buttons_de() = compose.scrolled("ride_end_buttons_de", scroll = Scroll.ToEnd, bars = SystemBars.BUTTONS) {
        RideDetailContent(RideDetailUiState(detailRide, commute, charts), rideActions)
    }

    private fun monthStats() = statsUiState(novemberRides(), groups, StatsPeriod.MONTH, LocalDate.of(2026, 11, 14), 400, 30.0, heat())

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun stats_collapsing_de() = compose.scrolled("stats_collapsing_de", Tab.RIDES, Scroll.COLLAPSING) { StatsContent(monthStats(), statsActions, PreviewNavigator) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun stats_scrolled_de() = compose.scrolled("stats_scrolled_de", Tab.RIDES, Scroll.UNDER) { StatsContent(monthStats(), statsActions, PreviewNavigator) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun stats_end_de() = compose.scrolled("stats_end_de", Tab.RIDES, Scroll.ToEnd) { StatsContent(monthStats(), statsActions, PreviewNavigator) }

    /** The commute with three weeks of rides on it. */
    private fun busyCommute() = commute.copy(rides = weeksOfRides().filter { it.shape == commuteShape })

    private val routeActions = object : RouteActions {
        override fun back() {}
        override fun rename() {}
        override fun openRide(id: String) {}
    }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun route_scrolled_de() = compose.scrolled("route_scrolled_de", scroll = Scroll.UNDER) { RouteContent(busyCommute(), routeActions) }

    @Test @Config(qualifiers = "de-$SCROLL_PHONE")
    fun route_end_de() = compose.scrolled("route_end_de", scroll = Scroll.ToEnd) { RouteContent(busyCommute(), routeActions) }
}
