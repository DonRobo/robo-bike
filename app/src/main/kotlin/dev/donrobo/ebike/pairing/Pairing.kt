package dev.donrobo.ebike.pairing

import dev.donrobo.ebike.settings.Bike
import java.util.UUID

/**
 * Pure decisions about displays and bikes (A2, E1, E6). The Android side (companion association,
 * presence events, scanning) lives in [dev.donrobo.ebike.BikeCompanion] and calls these.
 *
 * The link between a bike and its display is the bike's display MAC ([Bike.displayMac]); the system's
 * companion associations are matched to bikes by that MAC, nothing else is stored.
 */
object Pairing {

    /**
     * Advertised names of displays the app knows. The EKD01 advertises [EKD01_ADVERTISED_NAME] (trailing space) and
     * only the HID service ([ADVERTISED_SERVICE]), so name matching is the only reliable filter. Other BikeGo
     * displays are found through the wider "other display" search.
     */
    val KNOWN_NAME_PREFIXES = listOf("EKD01")

    /** The EKD01's advertised local name, trailing space included (docs/PROTOCOL.md, advertisement). */
    const val EKD01_ADVERTISED_NAME = "EKD01-BF "

    /** The only service UUID the display advertises: HID (0x1812). Its UART service is not in the advertisement. */
    val ADVERTISED_SERVICE: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")

    /**
     * Exact names for a system scan filter, which cannot match a prefix: the advertised name and its trimmed
     * form (what a cached device name may hold). The result still goes through [isScanCandidate].
     */
    val SCAN_FILTER_NAMES: List<String> = listOf(EKD01_ADVERTISED_NAME, EKD01_ADVERTISED_NAME.trim())

    /** Companion chooser pattern for the default search (CDM matches it against the advertised name). */
    const val KNOWN_NAME_PATTERN = "EKD01.*"

    /** Wider search: any device that advertises a name. */
    const val ANY_NAME_PATTERN = ".+"

    fun isKnownDisplay(name: String?): Boolean {
        val n = name?.trim() ?: return false
        return KNOWN_NAME_PREFIXES.any { n.startsWith(it, ignoreCase = true) }
    }

    /** "EKD01" from "EKD01-BF " (model part of the advertised name); the trimmed name if it has no dash. */
    fun modelName(name: String?): String? = name?.trim()?.substringBefore('-')?.trim()?.takeIf { it.isNotEmpty() }

    /** Bikes that have a display. Setup is needed on first launch when there is none. */
    fun needsSetup(bikes: List<Bike>): Boolean = bikes.none { it.displayMac != null }

    /**
     * Repairs bikes that lost their MAC while the system still has the association (e.g. data from before the
     * per-bike layout): companion associations that belong to no bike are given, in order, to bikes without a
     * display. Returns (bikeId, mac) pairs to store. Never touches a bike that has a MAC.
     */
    fun reconcile(bikes: List<Bike>, associatedMacs: List<String>): List<Pair<String, String>> {
        val owned = bikes.mapNotNull { it.displayMac?.uppercase() }.toSet()
        val orphans = associatedMacs.map { it.uppercase() }.distinct().filter { it !in owned }
        val withoutDisplay = bikes.filter { it.displayMac == null }
        return withoutDisplay.zip(orphans) { b, mac -> b.id to mac }
    }

    /** The bike whose display has this MAC, if any. */
    fun bikeFor(bikes: List<Bike>, mac: String?): Bike? =
        mac?.let { m -> bikes.firstOrNull { it.displayMac.equals(m, ignoreCase = true) } }

    /** What setup step 1 pairs: re-pair the first bike without a display (first run), or add a new bike. */
    sealed interface Target {
        data class Existing(val bikeId: String) : Target
        data object NewBike : Target
    }

    /**
     * First run (no bike has a display yet): the first bike without a display is reused, so the bike created by the
     * migration (or one whose display was forgotten) keeps its rides and settings. Otherwise setup adds a bike.
     */
    fun setupTarget(bikes: List<Bike>): Target =
        if (needsSetup(bikes)) bikes.firstOrNull()?.let { Target.Existing(it.id) } ?: Target.NewBike else Target.NewBike

    /** Result of giving a freshly paired display to a bike. */
    sealed interface Assign {
        /** Store [mac] on [bikeId] (an existing bike) or create a bike ([bikeId] null). */
        data class Store(val bikeId: String?, val mac: String) : Assign
        /** The display already belongs to [bikeId]; nothing changes except selecting that bike. */
        data class AlreadyOwned(val bikeId: String) : Assign
    }

    fun assign(bikes: List<Bike>, target: Target, mac: String): Assign {
        val m = mac.uppercase()
        val owner = bikeFor(bikes, m)
        val targetId = (target as? Target.Existing)?.bikeId
        return when {
            owner != null && owner.id != targetId -> Assign.AlreadyOwned(owner.id)
            else -> Assign.Store(targetId, m)
        }
    }

    /** What to do when the system reports that a paired display started advertising (bike switched on). */
    sealed interface Appeared {
        /** Make [bikeId] the active bike and start the connection. */
        data class Connect(val bikeId: String) : Appeared
        /** Do nothing; [reason] is logged. */
        data class Ignore(val reason: String) : Appeared
    }

    /**
     * A2: whichever paired display appears selects its bike. A running session with another bike is never
     * interrupted (only one display is ever connected).
     *
     * @param sessionBikeId the bike the service is connected or connecting to, null if none
     * @param paused bikes whose connection the rider paused with Disconnect (E5)
     */
    fun onAppeared(
        bikes: List<Bike>,
        mac: String?,
        autoConnect: Boolean,
        sessionBikeId: String?,
        paused: Set<String>,
    ): Appeared {
        val bike = bikeFor(bikes, mac) ?: return Appeared.Ignore("display $mac belongs to no bike")
        return when {
            !autoConnect -> Appeared.Ignore("auto-connect off")
            bike.id in paused -> Appeared.Ignore("paused by Disconnect until ${bike.name} is switched off")
            sessionBikeId != null && sessionBikeId != bike.id -> Appeared.Ignore("already connected to another bike")
            else -> Appeared.Connect(bike.id)
        }
    }

    /** E5: Disconnect lasts until the display stops advertising. Returns the bike whose pause ends, if any. */
    fun onDisappeared(bikes: List<Bike>, mac: String?, paused: Set<String>): String? =
        bikeFor(bikes, mac)?.id?.takeIf { it in paused }

    /** Fallback scan (no MAC known): a known display name whose address is not another bike's display. */
    fun isScanCandidate(name: String?, address: String, bikes: List<Bike>, forBikeId: String?): Boolean {
        if (!isKnownDisplay(name)) return false
        val owner = bikeFor(bikes, address)
        return owner == null || owner.id == forBikeId
    }

    /** Default name for a new bike: the localised default for the first, "<n>" numbered after that. */
    fun defaultName(existing: List<Bike>, first: String, numbered: (Int) -> String): String =
        if (existing.isEmpty()) first else generateSequence(existing.size + 1) { it + 1 }
            .map(numbered).first { n -> existing.none { it.name.equals(n, ignoreCase = true) } }
}

/** How close the display is, from the scan RSSI (setup step 1). */
enum class Signal { GOOD, OK, WEAK }

fun signal(rssi: Int): Signal = when {
    rssi >= -65 -> Signal.GOOD
    rssi >= -82 -> Signal.OK
    else -> Signal.WEAK
}

/** One advertising device seen by the setup scan. */
data class SeenDevice(val address: String, val name: String?, val rssi: Int)

/** The display setup offers: the strongest known display that is not already another bike's. */
fun pickDisplay(seen: Collection<SeenDevice>, bikes: List<Bike>, forBikeId: String?): SeenDevice? =
    seen.filter { Pairing.isScanCandidate(it.name, it.address, bikes, forBikeId) }.maxByOrNull { it.rssi }
