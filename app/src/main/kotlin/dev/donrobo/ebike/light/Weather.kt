package dev.donrobo.ebike.light

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Current precipitation as Open-Meteo reports it: [rateMmH] is the precipitation of the last interval
 * (15 min) scaled to mm per hour, [weatherCode] the WMO code. Either may be missing.
 */
data class Precipitation(val rateMmH: Double?, val weatherCode: Int?) {
    val wet: Boolean get() = (rateMmH ?: 0.0) > 0 || weatherCode?.let(Weather::isWetCode) == true
}

/**
 * Rain sensitivity (L4). A level matches when the rate reaches [minMmH] or the weather code is one of
 * [codes] (the code covers the case where the model reports a type but rounds the amount to 0).
 *
 * Thresholds (rate in mm/h, after the usual meteorological classes: light < 2.5, moderate 2.5–7.6,
 * heavy > 7.6 mm/h):
 * - DRIZZLE ("ab Nieseln"): anything falling at all, every wet WMO code (51–57 drizzle included).
 *   This is what the app did before the levels existed.
 * - RAIN: from 0.5 mm/h, or a rain/shower/snow/thunderstorm code (61–67, 71–77, 80–86, 95–99), not drizzle.
 * - HEAVY ("Starkregen"): from 7.6 mm/h, or a code for heavy rain, heavy freezing rain, heavy snow,
 *   violent showers, heavy snow showers or a thunderstorm (65, 67, 75, 82, 86, 95–99).
 */
enum class RainLevel(val minMmH: Double, val codes: Set<Int>) {
    DRIZZLE(0.0, codes(51..57, 61..67, 71..77, 80..86, 95..99)),
    RAIN(0.5, codes(61..67, 71..77, 80..86, 95..99)),
    HEAVY(7.6, setOf(65, 67, 75, 82, 86, 95, 96, 97, 99));

    fun matches(p: Precipitation): Boolean {
        val rate = p.rateMmH
        val byRate = rate != null && rate > 0 && rate >= minMmH
        return byRate || p.weatherCode in codes
    }
}

private fun codes(vararg ranges: IntRange): Set<Int> = ranges.flatMap { it }.toSet()

/** One answer of Open-Meteo's `current` block: precipitation (rain rule) and air temperature 2 m above ground (ride log). */
data class CurrentWeather(val precipitation: Precipitation?, val temperatureC: Double?)

/**
 * The last [CurrentWeather] per rounded position ([Weather.key]), so the light automation and the ride log share
 * one request: an answer younger than the caller's `maxAgeMs` is reused, and concurrent callers wait for the
 * request in flight instead of sending their own. Failures are not cached.
 */
class WeatherCache(
    private val fetch: suspend (lat: Double, lon: Double) -> CurrentWeather?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var last: Triple<String, Long, CurrentWeather?>? = null

    suspend fun get(lat: Double, lon: Double, maxAgeMs: Long): CurrentWeather? = lock.withLock {
        val key = Weather.key(lat, lon)
        last?.let { (k, at, w) -> if (k == key && clock() - at in 0..maxAgeMs) return@withLock w }
        fetch(lat, lon).also { last = Triple(key, clock(), it) }
    }
}

/** Current weather from Open-Meteo (no API key). */
object Weather {
    /** The position as sent: rounded to 2 decimals (~1 km) so the exact position never leaves the phone. */
    fun key(lat: Double, lon: Double): String = String.format(Locale.US, "latitude=%.2f&longitude=%.2f", lat, lon)

    fun url(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?${key(lat, lon)}" +
            "&current=temperature_2m,precipitation,rain,showers,snowfall,weather_code"

    /** An answer younger than this is reused by every caller ([WeatherCache]). */
    const val CACHE_MS = 5 * 60_000L

    private val cache = WeatherCache(::fetch)

    /** Current weather at the rounded position, from [cache] when fresh enough; throws on any network/parse failure. */
    suspend fun current(lat: Double, lon: Double, maxAgeMs: Long = CACHE_MS): CurrentWeather? = cache.get(lat, lon, maxAgeMs)

    /** WMO groups: drizzle 51-57, rain 61-67, snow 71-77, showers 80-86, thunderstorm 95-99. */
    fun isWetCode(code: Int) = code in RainLevel.DRIZZLE.codes

    /**
     * Builds [Precipitation] from the `current` block. Amounts are sums over [intervalS] (Open-Meteo:
     * 900 s); snowfall is in cm of snow, ~1/7 of that in mm of water. Null if nothing usable came back.
     */
    fun precipitation(
        weatherCode: Int?, precipitationMm: Double?, rainMm: Double?, showersMm: Double?, snowfallCm: Double?, intervalS: Int?,
    ): Precipitation? {
        val parts = listOfNotNull(rainMm, showersMm, snowfallCm?.let { it * 10 / 7 })
        val mm = precipitationMm ?: parts.takeIf { it.isNotEmpty() }?.sum()
        if (mm == null && weatherCode == null) return null
        val perHour = 3600.0 / (intervalS?.takeIf { it > 0 } ?: 900)
        return Precipitation(mm?.let { it * perHour }, weatherCode)
    }

    /** Blocking fetch on IO; throws on any network/parse failure. Callers use [current]. */
    private suspend fun fetch(lat: Double, lon: Double): CurrentWeather? = withContext(Dispatchers.IO) {
        val conn = URL(url(lat, lon)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val cur = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            fun d(k: String) = if (cur.isNull(k)) null else cur.optDouble(k).takeUnless { it.isNaN() }
            fun i(k: String) = if (cur.isNull(k) || !cur.has(k)) null else cur.optInt(k)
            val p = precipitation(i("weather_code"), d("precipitation"), d("rain"), d("showers"), d("snowfall"), i("interval"))
            val t = d("temperature_2m")
            if (p == null && t == null) null else CurrentWeather(p, t)
        } finally {
            conn.disconnect()
        }
    }
}
