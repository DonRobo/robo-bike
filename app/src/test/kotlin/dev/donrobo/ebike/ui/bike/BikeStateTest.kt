package dev.donrobo.ebike.ui.bike

import dev.donrobo.ebike.errors.ErrorEntry
import dev.donrobo.ebike.location.GeoPoint
import dev.donrobo.ebike.location.LocationFix
import dev.donrobo.ebike.location.LocationPermissions
import dev.donrobo.ebike.location.ParkedPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BikeStateTest {
    private fun live(at: Long, hex: String) = ErrorEntry(ErrorEntry.Source.LIVE, at, 1284.0, null, hex)
    private fun hist(at: Long, hex: String) = ErrorEntry(ErrorEntry.Source.HISTORY, at, null, null, hex)

    @Test fun errorListLiveAndHistoryEntries() {
        val entries = listOf(live(3_000, "21"), hist(2_000, "3330"), live(1_000, "2f")) // newest first
        val e = errorSection(true, entries, currentRaw = 0, live = false)!!
        assertEquals(ErrorHealth.OK, e.health)
        assertNull(e.current)
        assertEquals(listOf(21, 30, null), e.history.map { it.code })   // BCD 0x21, ASCII "30", 0x2f undecodable
        assertEquals(listOf(true, true, false), e.history.map { it.known })
        assertEquals(ErrorEntry.Source.HISTORY, e.history[1].source)
        assertEquals("2f", e.history[2].rawHex)
    }

    @Test fun unknownCodeKeepsItsNumber() {
        val r = errorSection(true, listOf(live(1, "99")), 0, false)!!.history.single()
        assertEquals(99, r.code)
        assertEquals(false, r.known)
    }

    @Test fun currentErrorIsTakenOutOfTheHistory() {
        val entries = listOf(live(3_000, "21"), live(1_000, "21"))
        val e = errorSection(true, entries, currentRaw = 0x21, live = true, currentSinceMs = 2_900)!!
        assertEquals(ErrorHealth.ERROR, e.health)
        assertEquals(21, e.current!!.code)
        assertEquals(2_900L, e.current!!.atMs)                           // from DriveErrorState while live
        assertEquals(listOf(1_000L), e.history.map { it.atMs })
        val offline = errorSection(true, entries, currentRaw = 0x21, live = false)!!
        assertEquals(3_000L, offline.current!!.atMs)
        assertEquals(false, offline.currentIsLive)
    }

    @Test fun errorSectionHiddenOrUnknown() {
        assertNull(errorSection(false, emptyList(), 0, true))
        assertEquals(ErrorHealth.UNKNOWN, errorSection(true, emptyList(), null, false)!!.health)
    }

    @Test fun parkedStates() {
        val all = LocationPermissions(coarse = true, fine = true, background = true)
        val inUse = all.copy(background = false)
        val p = GeoPoint(48.2, 16.37)
        assertEquals(ParkedUi.ConnectedNow, parkedUi(true, ParkedPosition(1, LocationFix(p, 1)), all))
        assertEquals(ParkedUi.Never, parkedUi(false, null, all))
        assertEquals(ParkedUi.NoPosition(5, NoPositionReason.NEEDS_BACKGROUND), parkedUi(false, ParkedPosition(5, null), inUse))
        assertEquals(ParkedUi.NoPosition(5, NoPositionReason.NO_PERMISSION), parkedUi(false, ParkedPosition(5, null), LocationPermissions.NONE))
        assertEquals(ParkedUi.NoPosition(5, NoPositionReason.UNAVAILABLE), parkedUi(false, ParkedPosition(5, null), all))
        val at = 10_000_000L
        assertEquals(ParkedUi.Known(at, p, null, null), parkedUi(false, ParkedPosition(at, LocationFix(p, at - 30_000, 20f)), all))
        assertEquals(ParkedUi.Known(at, p, at - 3_600_000, 800), parkedUi(false, ParkedPosition(at, LocationFix(p, at - 3_600_000, 800f)), all))
    }

    @Test fun relativeDays() {
        val today = LocalDate.of(2026, 11, 14)
        assertEquals(RelativeDay.TODAY, relativeDay(today, today))
        assertEquals(RelativeDay.YESTERDAY, relativeDay(today.minusDays(1), today))
        assertEquals(RelativeDay.EARLIER, relativeDay(today.minusDays(2), today))
    }
}
