package dev.donrobo.ebike

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one connection status ([connectionStatus]) every surface shows. */
class ConnectionStatusTest {
    private fun status(
        bike: String? = "a", active: String? = "a", session: String? = null, conn: ConnState = ConnState.OFF,
        running: Boolean = false, lost: Boolean = false, armed: Boolean = false,
    ) = connectionStatus(bike, active, session, conn, running, lost, armed)

    @Test fun mapping() {
        assertEquals(ConnectionStatus.CONNECTED, status(session = "a", conn = ConnState.CONNECTED, running = true))
        assertEquals(ConnectionStatus.CONNECTING, status(session = "a", conn = ConnState.CONNECTING, running = true))
        assertEquals(ConnectionStatus.WAITING, status(conn = ConnState.SCANNING, running = true))
        assertEquals(ConnectionStatus.WAITING, status(running = true))
        assertEquals(ConnectionStatus.OFF, status())
    }

    /** Service stopped, but the system will start it when the display appears: waiting (was "Nicht verbunden" in the widget). */
    @Test fun waitingWhileAutoConnectIsArmed() {
        assertEquals(ConnectionStatus.WAITING, status(armed = true))
        assertEquals(ConnectionStatus.OFF, status(armed = false))
    }

    /** After a drop the reconnect attempts stay "lost" in every surface, not a flicker of connecting / waiting. */
    @Test fun lostAfterADrop() {
        assertEquals(ConnectionStatus.LOST, status(running = true, lost = true))
        assertEquals(ConnectionStatus.LOST, status(session = "a", conn = ConnState.CONNECTING, running = true, lost = true))
        // reconnected: connected wins over the flag; a stopped service is not lost any more
        assertEquals(ConnectionStatus.CONNECTED, status(session = "a", conn = ConnState.CONNECTED, running = true, lost = true))
        assertEquals(ConnectionStatus.OFF, status(running = false, lost = true))
        assertEquals(ConnectionStatus.WAITING, status(running = false, lost = true, armed = true))
    }

    @Test fun perBike() {
        // connected to the city bike (a): the MTB (b) shows as not connected, or waiting for its display
        assertEquals(ConnectionStatus.CONNECTED, status(session = "a", conn = ConnState.CONNECTED, running = true, armed = true))
        assertEquals(ConnectionStatus.WAITING, status("b", "b", "a", ConnState.CONNECTED, running = true, armed = true))
        assertEquals(ConnectionStatus.OFF, status("b", "b", "a", ConnState.CONNECTED, running = true, armed = false))
        assertEquals(ConnectionStatus.OFF, status("b", "b", "a", ConnState.CONNECTED, running = true, lost = true))
        // service running, no session yet: it waits for the active bike only
        assertEquals(ConnectionStatus.WAITING, status("b", "b", null, ConnState.OFF, running = true))
        assertEquals(ConnectionStatus.OFF, status("b", "a", null, ConnState.OFF, running = true))
    }
}
