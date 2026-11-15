package dev.donrobo.ebike.ui.stats

import dev.donrobo.ebike.ride.RouteGroups
import dev.donrobo.ebike.ride.StatsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StatsStateTest {
    @Test fun statsTellsRecordingOffFromNoPositions() {
        val today = LocalDate.of(2026, 11, 14)
        assertEquals(NoTrackReason.RECORDING_OFF, statsUiState(emptyList(), RouteGroups.EMPTY, StatsPeriod.MONTH, today, 0, 30.0, null).noTrackReason)
        assertEquals(NoTrackReason.NO_POSITIONS, statsUiState(emptyList(), RouteGroups.EMPTY, StatsPeriod.MONTH, today, 0, 30.0, null, gpsTrack = true).noTrackReason)
    }

    @Test fun priceInput() {
        assertEquals(30.5, parsePrice("30,5")!!, 0.0)
        assertEquals(31.0, parsePrice(" 31 ")!!, 0.0)
        assertNull(parsePrice("0"))
        assertNull(parsePrice("abc"))
        assertNull(parsePrice("1000"))
    }
}
