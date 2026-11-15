package dev.donrobo.ebike.ui.light

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.donrobo.ebike.R
import dev.donrobo.ebike.light.LightControl
import dev.donrobo.ebike.light.LightMode
import dev.donrobo.ebike.light.LightReason
import dev.donrobo.ebike.light.LightStatus
import dev.donrobo.ebike.light.RainLevel
import dev.donrobo.ebike.light.RainNow
import dev.donrobo.ebike.light.SunDay
import dev.donrobo.ebike.light.SunSchedule
import dev.donrobo.ebike.light.SwitchPoint
import dev.donrobo.ebike.light.lightCardStatus
import dev.donrobo.ebike.proto.BikeReport
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import dev.donrobo.ebike.ui.components.currentLocale

/** Today's curve at the rider's position; null in [LightUiState.curve] while no position is known. */
class CurveUi(val day: SunDay, val nowMinute: Int)

/** Everything the Licht screen shows; built by [lightUiState], rendered by [LightContent]. */
data class LightUiState(
    val live: Boolean,
    val lightOn: Boolean,
    val status: LightStatus,
    val mode: LightMode,
    val rainEnabled: Boolean,
    val rainLevel: RainLevel,
    val morningOffDeg: Double,
    val eveningOnDeg: Double,
    val curve: CurveUi?,
    /** Q4: the display has a lamp switch. Without one the screen explains that instead of offering automation. */
    val lampSwitch: Boolean = true,
) {
    val schedule: SunSchedule? = curve?.day?.schedule(morningOffDeg, eveningOnDeg)
}

/** [liveReport]: this bike's live report; null while it is not connected. */
fun lightUiState(
    liveReport: BikeReport?, status: LightStatus, mode: LightMode, rainEnabled: Boolean, rainLevel: RainLevel,
    morningOffDeg: Double, eveningOnDeg: Double, curve: CurveUi?, lampSwitch: Boolean = true,
) = LightUiState(liveReport != null, LightControl.lampOn(liveReport), lightCardStatus(liveReport != null, status), mode, rainEnabled, rainLevel, morningOffDeg, eveningOnDeg, curve, lampSwitch)

/** What a switching point's text block says. */
sealed interface PointText {
    data class At(val time: LocalTime, val deg: Int, val reached: Boolean, val above: Boolean) : PointText
    /** No switch today: on (or off) all day. */
    data class AllDay(val on: Boolean, val deg: Int) : PointText
    /** No curve (no position): only the elevation. */
    data class DegOnly(val deg: Int) : PointText
}

fun pointText(point: SwitchPoint?, deg: Double, schedule: SunSchedule?, day: SunDay?): PointText {
    val d = deg.roundToInt()
    if (point == null || schedule == null || day == null) return PointText.DegOnly(d)
    val time = point.time ?: return PointText.AllDay(schedule.on.first(), d)
    return PointText.At(time, d, point.reached, deg > day.maxDeg)
}

@Composable
fun formatTime(t: LocalTime): String {
    val locale = currentLocale()
    return t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
}

private fun signed(d: Int) = if (d < 0) "−${-d}" else "$d"

@Composable
fun PointText.time(): String = when (this) {
    is PointText.At -> formatTime(time)
    else -> stringResource(R.string.light_time_none)
}

@Composable
fun PointText.detail(): String = when (this) {
    is PointText.At -> when {
        !reached && above -> stringResource(R.string.light_sun_not_reached, signed(deg))
        !reached -> stringResource(R.string.light_sun_not_low, signed(deg))
        else -> sunDeg(deg)
    }
    is PointText.AllDay -> stringResource(if (on) R.string.light_all_day_on else R.string.light_all_day_off)
    is PointText.DegOnly -> sunDeg(deg)
}

@Composable
private fun sunDeg(d: Int) = if (d >= 0) stringResource(R.string.light_sun_above, d) else stringResource(R.string.light_sun_below, -d)

/** Long reason for the Licht screen's status card (L6). */
@Composable
fun lightLongText(s: LightStatus, on: Boolean): String {
    val why = when (s.reason) {
        LightReason.NOT_CONNECTED -> R.string.light_why_offline
        LightReason.LOCKED -> R.string.light_why_locked
        LightReason.MODE_OFF -> R.string.light_why_mode_off
        LightReason.FAILED -> R.string.light_why_failed
        LightReason.MANUAL -> if (on) R.string.light_why_manual_on else R.string.light_why_manual_off
        LightReason.ALWAYS_ON -> R.string.light_why_always
        LightReason.DARK -> R.string.light_why_dark
        LightReason.RAIN -> R.string.light_why_rain
        LightReason.DAY -> R.string.light_why_day
        LightReason.NO_POSITION -> R.string.light_why_no_position
    }
    val withRain = s.reason == LightReason.DARK || s.reason == LightReason.DAY || s.reason == LightReason.NO_POSITION
    if (!withRain) return stringResource(why)
    val rain = when (s.rain) {
        RainNow.DISABLED -> R.string.light_rain_disabled
        RainNow.UNKNOWN -> R.string.light_rain_unknown
        RainNow.DRY -> R.string.light_rain_dry
        RainNow.BELOW_LEVEL -> R.string.light_rain_below
        RainNow.WET -> R.string.light_rain_wet
    }
    return stringResource(why) + " " + stringResource(rain)
}

/** Short reason: subtitle of the Start screen's light card ("Automatik, dunkel seit 16:06"). */
@Composable
fun lightShortText(s: LightStatus, on: Boolean): String = when (s.reason) {
    LightReason.NOT_CONNECTED -> stringResource(R.string.light_short_offline)
    LightReason.LOCKED -> stringResource(R.string.light_short_locked)
    LightReason.MODE_OFF -> stringResource(R.string.light_short_mode_off)
    LightReason.FAILED -> stringResource(R.string.light_short_failed)
    LightReason.MANUAL -> stringResource(if (on) R.string.light_short_manual_on else R.string.light_short_manual_off)
    LightReason.ALWAYS_ON -> stringResource(R.string.light_short_always)
    LightReason.DARK -> when {
        s.since != null -> stringResource(R.string.light_short_dark_since, formatTime(s.since))
        s.until != null -> stringResource(R.string.light_short_dark_until, formatTime(s.until))
        else -> stringResource(R.string.light_short_dark)
    }
    LightReason.RAIN -> stringResource(R.string.light_short_rain)
    LightReason.DAY -> s.until?.let { stringResource(R.string.light_short_day_until, formatTime(it)) } ?: stringResource(R.string.light_short_day)
    LightReason.NO_POSITION -> stringResource(R.string.light_short_no_position)
}
