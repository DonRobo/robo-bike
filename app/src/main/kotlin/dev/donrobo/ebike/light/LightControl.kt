package dev.donrobo.ebike.light

import dev.donrobo.ebike.proto.BikeReport

/**
 * The hand light switch as every surface shows it (L1, Q4): Start, Lenkermodus, widget, quick-settings tile and
 * connection notification. One rule, see [ManualLight] for the command itself.
 */
enum class LightControl {
    ON,
    OFF,
    /** Connected, but locked: BikeGo never sends the lamp command to a locked bike, neither do we. */
    LOCKED,
    NOT_CONNECTED,
    /** The display has no lamp switch (feature block). */
    NO_LAMP;

    /** The rider can switch the light from here right now. */
    val switchable: Boolean get() = this == ON || this == OFF

    companion object {
        /** [liveReport]: the connected bike's report ([dev.donrobo.ebike.Repo.live]); null while not connected. */
        fun of(liveReport: BikeReport?, lampSwitch: Boolean = true): LightControl = when {
            !lampSwitch -> NO_LAMP
            liveReport == null -> NOT_CONNECTED
            !liveReport.unlocked -> LOCKED
            liveReport.lightOn -> ON
            else -> OFF
        }

        /**
         * Whether to show the lamp as on. Only a live report knows the lamp state; a last known report may be
         * stale, so without a connection the lamp is shown in the neutral off presentation.
         */
        fun lampOn(liveReport: BikeReport?): Boolean = liveReport?.lightOn == true
    }
}
