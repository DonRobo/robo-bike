package dev.donrobo.ebike.notify

import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import dev.donrobo.ebike.ConnectionStatus
import dev.donrobo.ebike.R
import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.textRes
import dev.donrobo.ebike.proto.BikeReport
import dev.donrobo.ebike.proto.ErrorCodes
import dev.donrobo.ebike.range.RangeEstimate
import dev.donrobo.ebike.settings.Bike
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.units.UnitFormatter
import dev.donrobo.ebike.units.UnitSystem
import dev.donrobo.ebike.widget.BikeWidget
import dev.donrobo.ebike.widget.bikeGlance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** Copy of the Widgets artboard and the alert de-duplication against the real resources and NotificationManager. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class NotifyTextTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val fmt = UnitFormatter(UnitSystem.METRIC, Locale.GERMANY)
    private val bike = Bike("b1", "Stadtrad", null)
    private val report = BikeReport(lightOn = true, gear = 3, gears = 5, batteryPct = 75, speedKmh = 21.0, tripKm = 1.0, odoKm = 10.0)
    private val range = RangeEstimate(64.0, 86.0, 1.2)

    private fun connected(r: BikeReport, range: RangeEstimate? = null) = bikeGlance(bike, ConnectionStatus.CONNECTED, Snapshot.live(r), range, fmt)
    private fun offline(status: ConnectionStatus) = bikeGlance(bike, status, null, null, fmt)

    @Test fun connectionNoticeMatchesArtboard() {
        val n = connectionNotice(ctx, connected(report, range), details = true)
        assertEquals("Stadtrad ist verbunden", n.title)
        assertEquals("75 % Akku, noch 64 km, Licht an", n.text)
        assertEquals(false, n.lightAction) // "Licht aus"
    }

    @Test fun connectionNoticeWithoutRangeOrDetails() {
        val g = connected(report.copy(lightOn = false))
        assertEquals("75 % Akku, Licht aus", connectionNotice(ctx, g, true).text)
        assertEquals(true, connectionNotice(ctx, g, true).lightAction)
        val quiet = connectionNotice(ctx, g, false)
        assertEquals("Stadtrad ist verbunden", quiet.title)
        assertNull(quiet.text)
        assertNull(quiet.lightAction)
        // without details the content does not depend on the live values, so it never changes during a ride
        assertEquals(quiet, connectionNotice(ctx, connected(report.copy(batteryPct = 12), range), false))
    }

    @Test fun lockedBikeHasNoLightAction() {
        val n = connectionNotice(ctx, connected(report.copy(unlocked = false)), true)
        assertNull(n.lightAction)
        assertEquals("75 % Akku, Licht an, gesperrt", n.text)
    }

    @Test fun notConnected() {
        val n = connectionNotice(ctx, offline(ConnectionStatus.WAITING), true)
        assertEquals("Stadtrad", n.title)
        assertEquals("Wartet auf das Rad", n.text)
        assertNull(n.lightAction)
    }

    /** After a drop the notification says why; the widget the short form. Same meaning as the in-app status. */
    @Test fun lostConnection() {
        val g = offline(ConnectionStatus.LOST)
        assertEquals("Getrennt · Rad aus oder außer Reichweite", connectionNotice(ctx, g, true).text)
        assertEquals("Getrennt", ctx.getString(BikeWidget.statusText(g)))
        assertEquals("Getrennt · Rad aus oder außer Reichweite", ctx.getString(ConnectionStatus.LOST.textRes(detail = true)))
    }

    /** One string set for every surface (E4): German "Verbindet …" everywhere. */
    @Test fun oneTextPerState() {
        assertEquals("Verbindet …", connectionNotice(ctx, offline(ConnectionStatus.CONNECTING), true).text)
        assertEquals("Verbindet …", ctx.getString(BikeWidget.statusText(offline(ConnectionStatus.CONNECTING))))
        assertEquals("Verbindet …", ctx.getString(ConnectionStatus.CONNECTING.textRes(detail = true)))
        assertEquals("Wartet auf das Rad", ctx.getString(BikeWidget.statusText(offline(ConnectionStatus.WAITING))))
        assertEquals("Nicht verbunden", ctx.getString(BikeWidget.statusText(offline(ConnectionStatus.OFF))))
        assertEquals("Verbunden", ctx.getString(BikeWidget.statusText(connected(report))))
        assertEquals("Verbunden, gesperrt", ctx.getString(BikeWidget.statusText(connected(report.copy(unlocked = false)))))
    }

    @Test fun homeAlertMatchesArtboard() {
        val a = alertContent(ctx, "Stadtrad", Alert.HomeRange(9.0, 7.0), fmt)
        assertEquals("Der Akku wird knapp für den Heimweg", a.title)
        assertEquals("Noch 9 km Reichweite, 7 km bis nach Hause", a.text)
    }

    @Test fun maintenanceAndCharge() {
        assertEquals("Fällig in 46 km", alertContent(ctx, null, Alert.MaintenanceDue("chain", "Kette schmieren", 46.0), fmt).text)
        assertEquals("Überfällig seit 12 km", alertContent(ctx, null, Alert.MaintenanceDue("chain", "Kette schmieren", -12.0), fmt).text)
        assertEquals("Jetzt fällig", alertContent(ctx, null, Alert.MaintenanceDue("chain", "Kette schmieren", 0.3), fmt).text)
        assertEquals("Stadtrad aufladen", alertContent(ctx, "Stadtrad", Alert.ChargeReminder(18, "r1"), fmt).title)
    }

    @Test fun everyKnownErrorCodeHasItsOwnText() {
        val unknown = ctx.getString(R.string.error_unknown_title)
        for (code in ErrorCodes.KNOWN) assertNotEquals("code $code", unknown, driveErrorText(ctx, code).title)
        assertEquals(unknown, driveErrorText(ctx, 99).title)
        assertEquals(unknown, driveErrorText(ctx, null).title)
        assertEquals("Fehler 21", driveErrorLabel(ctx, 0x21, 21))
        assertEquals("Fehler 0xAB", driveErrorLabel(ctx, 0xAB, null))
    }

    @Test fun alertPostedOncePerEpisode() {
        val id = ctx.bikes.add("Stadtrad").id
        val nm = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        repeat(5) { Alerts.show(ctx, id, Alert.DriveError(0x21, 21)) }
        assertEquals(1, nm.allNotifications.size)
        val n = nm.allNotifications.single()
        assertEquals(NotifyChannel.DRIVE_ERROR.id, n.channelId)
        assertEquals(driveErrorText(ctx, 21).title, n.extras.getString("android.title"))

        // a drive error stays as a record after it cleared; the home warning goes away
        Alerts.resolve(ctx, id, AlertKind.DRIVE_ERROR)
        assertEquals(1, nm.allNotifications.size)
        Alerts.show(ctx, id, Alert.HomeRange(9.0, 7.0))
        assertEquals(2, nm.allNotifications.size)
        Alerts.resolve(ctx, id, AlertKind.HOME_RANGE)
        assertEquals(1, nm.allNotifications.size)
    }

    @Test fun kindSwitchedOffPostsNothing() {
        val id = ctx.bikes.add("Stadtrad").id
        ctx.settings[NotifySettings.maintenance] = false
        Alerts.show(ctx, id, Alert.MaintenanceDue("chain", "Kette schmieren", 3.0))
        assertEquals(0, shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }

    @Test fun widgetTexts() {
        val g = connected(report, range)
        assertEquals("75 % Akku", BikeWidget.batteryText(ctx, g))
        assertEquals(R.string.status_connected, BikeWidget.statusText(g))
        val none = bikeGlance(null, ConnectionStatus.OFF, null, null, fmt)
        assertEquals("Kein Rad eingerichtet", BikeWidget.batteryText(ctx, none))
    }
}
