package dev.donrobo.ebike

import dev.donrobo.ebike.proto.BikeReport

/**
 * The latest report of the connected bike (A2: one link, one bike). Published in [Repo.live] only while the link
 * is up, so "live" never needs a separate connection check.
 */
data class Live(val bikeId: String, val report: BikeReport)

/**
 * A bike's state as shown: its live report while it is the connected bike, else its [LastKnown] state (U2).
 * [asOfMs] is set when the values are the last known ones (when they were received).
 */
data class Snapshot(val report: BikeReport, val asOfMs: Long?) {
    val live: Boolean get() = asOfMs == null

    /** The report only while live (speed, assist level and lamp state are not shown from the last known state). */
    val liveReport: BikeReport? get() = report.takeIf { live }

    companion object {
        /** Pure: [live] counts only if it belongs to [bikeId]; otherwise that bike's [lastKnown]. */
        fun of(bikeId: String?, live: Live?, lastKnown: LastKnown?): Snapshot? =
            live?.takeIf { bikeId != null && it.bikeId == bikeId }?.let { Snapshot(it.report, null) }
                ?: lastKnown?.let { Snapshot(it.report, it.atMs) }

        /** Test helper / screenshot data: a live snapshot of [report]. */
        fun live(report: BikeReport) = Snapshot(report, null)
    }
}
