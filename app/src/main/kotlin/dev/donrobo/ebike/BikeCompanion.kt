package dev.donrobo.ebike

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import androidx.annotation.RequiresApi
import dev.donrobo.ebike.bike.DisplayInfoStore
import dev.donrobo.ebike.display.DisplayCapabilitiesStore
import dev.donrobo.ebike.pairing.Pairing
import dev.donrobo.ebike.settings.bikes
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.widget.BikeWidget
import java.util.regex.Pattern

/**
 * Companion Device Manager associations with the bikes' displays (A2: one association per bike, matched to the bike
 * by its display MAC). Once associated and observed, the system binds [PresenceService] whenever one of the displays
 * advertises, even with the app not running. Decisions are in [Pairing]; this object only talks to the system.
 */
@Suppress("DEPRECATION") // the String/MAC variants are the only ones on API 31/32 (and 33-35 for presence)
object BikeCompanion {
    private fun cdm(ctx: Context) = ctx.getSystemService(CompanionDeviceManager::class.java)

    fun supported(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /** MACs (upper case, like BluetoothDevice.address) of all displays this app is associated with. */
    fun associatedMacs(ctx: Context): List<String> {
        if (!supported(ctx)) return emptyList()
        val macs = if (Build.VERSION.SDK_INT >= 33) cdm(ctx).myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        else cdm(ctx).associations
        return macs.map { it.uppercase() }
    }

    @RequiresApi(33)
    private fun association(ctx: Context, mac: String): AssociationInfo? =
        cdm(ctx).myAssociations.lastOrNull { it.deviceMacAddress?.toString().equals(mac, ignoreCase = true) }

    @RequiresApi(33)
    fun macOf(ctx: Context, associationId: Int): String? =
        cdm(ctx).myAssociations.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()?.uppercase()

    /** MAC of the active bike's display if it is associated, or null. */
    fun associatedMac(ctx: Context): String? {
        val mac = ctx.bikes.active()?.displayMac ?: return null
        return mac.takeIf { m -> associatedMacs(ctx).any { it.equals(m, ignoreCase = true) } }
    }

    /** The active bike has no associated display: Connect first asks the system to pair one (E6). */
    fun needsAssociation(ctx: Context) = supported(ctx) && associatedMac(ctx) == null

    /** Which displays the system chooser offers. */
    sealed interface Search {
        /** Default: displays the app knows by name ([Pairing.KNOWN_NAME_PATTERN]). */
        data object Known : Search
        /** "Anderes Display suchen": every device that advertises a name. */
        data object Any : Search
        /** The display the app's own scan found: the system only asks to confirm it. */
        data class Device(val mac: String) : Search
    }

    /**
     * Starts the system chooser flow: [onPending] gets the IntentSender to launch from the activity. The result
     * arrives as the activity result; [macFromResult] reads the chosen display from it.
     */
    fun associate(ctx: Context, search: Search, onPending: (IntentSender) -> Unit, onFailure: (CharSequence?) -> Unit) {
        val filter = BluetoothLeDeviceFilter.Builder().apply {
            when (search) {
                Search.Known -> setNamePattern(Pattern.compile(Pairing.KNOWN_NAME_PATTERN))
                Search.Any -> setNamePattern(Pattern.compile(Pairing.ANY_NAME_PATTERN))
                is Search.Device -> setScanFilter(ScanFilter.Builder().setDeviceAddress(search.mac).build())
            }
        }.build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(search is Search.Device)
            .build()
        val cb = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)          // API 33+
            @Deprecated("API 31/32 only") override fun onDeviceFound(intentSender: IntentSender) = onPending(intentSender)
            override fun onAssociationCreated(info: AssociationInfo) = Unit // handled through the activity result
            override fun onFailure(error: CharSequence?) = onFailure(error)
        }
        if (Build.VERSION.SDK_INT >= 33) cdm(ctx).associate(request, ctx.mainExecutor, cb) else cdm(ctx).associate(request, cb, null)
    }

    /** The display chosen in the chooser: from the result extras, else the one association that is new since [before]. */
    fun macFromResult(ctx: Context, data: Intent?, before: List<String>): String? {
        val fromExtra = if (Build.VERSION.SDK_INT >= 33) {
            data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)?.deviceMacAddress?.toString()
        } else {
            when (val d = data?.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
                is ScanResult -> d.device.address
                is BluetoothDevice -> d.address
                else -> null
            }
        }
        return fromExtra?.uppercase() ?: (associatedMacs(ctx) - before.toSet()).singleOrNull()
    }

    /**
     * Gives a freshly paired display to a bike ([target]), creating the bike if needed, selects it and starts
     * observing. [name] renames/names the bike (null keeps the name; for a new bike null means [defaultName]).
     * A returned [Pairing.Assign.Store] always carries the bike's id (also for a new bike).
     */
    fun adopt(ctx: Context, target: Pairing.Target, mac: String, name: String?, defaultName: String): Pairing.Assign {
        val bikes = ctx.bikes
        val settings = ctx.settings
        val assign = Pairing.assign(bikes.all(), target, mac)
        val result = when (assign) {
            is Pairing.Assign.Store -> {
                val result = assign
                val id = result.bikeId?.also { id ->
                    if (!bikes.get(id)?.displayMac.equals(result.mac, ignoreCase = true)) {
                        DisplayCapabilitiesStore.clear(settings, id)
                        DisplayInfoStore.clear(settings, id)
                    }
                    bikes.setMac(id, result.mac)
                    if (!name.isNullOrBlank()) bikes.rename(id, name.trim())
                } ?: bikes.add(name?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName, result.mac).id
                bikes.setActive(id)
                settings.set(ConnectionSettings.pausedUntilOff, id, false)
                Repo.log("display ${result.mac} paired with bike $id")
                result.copy(bikeId = id)
            }
            is Pairing.Assign.AlreadyOwned -> {
                bikes.setActive(assign.bikeId)
                Repo.log("display $mac already belongs to bike ${assign.bikeId}, selected it")
                assign
            }
        }
        setObserving(ctx, settings[ConnectionSettings.autoConnect])
        return result
    }

    /** E3: the rider's "Automatisch verbinden" switch (Einstellungen, connection sheet): stores it and follows it. */
    fun setAutoConnect(ctx: Context, on: Boolean) {
        ctx.settings[ConnectionSettings.autoConnect] = on
        setObserving(ctx, on)
    }

    /** Starts/stops presence observation of every bike's associated display. */
    fun setObserving(ctx: Context, on: Boolean) {
        if (!supported(ctx)) return
        val associated = associatedMacs(ctx).toSet()
        ctx.bikes.all().mapNotNull { it.displayMac?.uppercase() }.filter { it in associated }.forEach { observe(ctx, it, on) }
    }

    private fun observe(ctx: Context, mac: String, on: Boolean) {
        try {
            val m = cdm(ctx)
            if (Build.VERSION.SDK_INT >= 36) {
                val id = association(ctx, mac)?.id ?: return
                val req = ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()
                if (on) m.startObservingDevicePresence(req) else m.stopObservingDevicePresence(req)
            } else {
                if (on) m.startObservingDevicePresence(mac) else m.stopObservingDevicePresence(mac)
            }
        } catch (e: Exception) {
            Repo.log("presence observation of $mac ${if (on) "start" else "stop"} failed: $e")
        }
    }

    private fun disassociate(ctx: Context, mac: String) {
        if (!supported(ctx) || associatedMacs(ctx).none { it.equals(mac, ignoreCase = true) }) return
        observe(ctx, mac, false)
        try {
            if (Build.VERSION.SDK_INT >= 33) association(ctx, mac)?.let { cdm(ctx).disassociate(it.id) } else cdm(ctx).disassociate(mac)
            Repo.log("disassociated display $mac")
        } catch (e: Exception) {
            Repo.log("disassociate $mac failed: $e")
        }
    }

    /**
     * E6 for one bike: stops observing, removes the association and the bike's display MAC (and what that display
     * supported). The bike, its rides and settings stay; its next Connect searches for a display again.
     */
    fun forget(ctx: Context, bikeId: String) {
        val bikes = ctx.bikes
        val bike = bikes.get(bikeId) ?: return
        bike.displayMac?.let { disassociate(ctx, it) }
        bikes.setMac(bikeId, null)
        DisplayCapabilitiesStore.clear(ctx.settings, bikeId)
        DisplayInfoStore.clear(ctx.settings, bikeId)
        ctx.settings.set(ConnectionSettings.pausedUntilOff, bikeId, false)
        if (Repo.sessionBikeId.value == bikeId) BikeService.stop(ctx)
    }

    /** E6 for the active bike. */
    fun forget(ctx: Context) { ctx.bikes.active()?.let { forget(ctx, it.id) } }

    /** Removes a bike entirely ([dev.donrobo.ebike.settings.Bikes.remove]: settings and rides) after releasing its display. */
    fun removeBike(ctx: Context, bikeId: String) {
        forget(ctx, bikeId)
        ctx.bikes.remove(bikeId)
        Repo.log("bike $bikeId removed")
    }

    /** Gives associations that belong to no bike to bikes without a display ([Pairing.reconcile]). Run at app start. */
    fun reconcile(ctx: Context) {
        val bikes = ctx.bikes
        for ((id, mac) in Pairing.reconcile(bikes.all(), associatedMacs(ctx))) {
            Repo.log("association $mac had no bike, given to bike $id")
            bikes.setMac(id, mac)
        }
    }
}

/** Bound by the system while a paired display is in range; starts [BikeService] for its bike when it appears. */
class PresenceService : CompanionDeviceService() {
    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val mac = BikeCompanion.macOf(this, event.associationId)
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED || event.event == DevicePresenceEvent.EVENT_BT_CONNECTED) appeared(mac)
        if (event.event == DevicePresenceEvent.EVENT_BLE_DISAPPEARED) disappeared(mac)
    }

    @RequiresApi(33) // only called from API 33 on
    @Deprecated("replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceDisappeared(info: AssociationInfo) = disappeared(info.deviceMacAddress?.toString())

    @Deprecated("replaced by onDeviceDisappeared(AssociationInfo) on API 33") override fun onDeviceDisappeared(address: String) = disappeared(address)

    /** A bike was switched off (its display stopped advertising): a Disconnect of that bike only lasts until then. */
    private fun disappeared(mac: String?) {
        val all = bikes.all()
        val id = Pairing.onDisappeared(all, mac, ConnectionSettings.pausedBikes(settings, all)) ?: return
        settings.set(ConnectionSettings.pausedUntilOff, id, false)
        Repo.log("display $mac gone, auto-connect for bike $id active again")
        runCatching { BikeWidget.refresh(this) } // "Nicht verbunden" becomes "Wartet auf das Rad" (auto-connect armed again)
    }

    // API 33-35 (overriding it means the String variant below is not called there)
    @RequiresApi(33) // only called from API 33 on
    @Deprecated("replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceAppeared(info: AssociationInfo) = appeared(info.deviceMacAddress?.toString())

    // API 31/32
    @Deprecated("replaced by onDeviceAppeared(AssociationInfo) on API 33") override fun onDeviceAppeared(address: String) = appeared(address)

    private fun appeared(mac: String?) {
        val all = bikes.all()
        val connected = Repo.sessionBikeId.value.takeIf { Repo.conn.value == ConnState.CONNECTED }
        when (val d = Pairing.onAppeared(all, mac, settings[ConnectionSettings.autoConnect], connected, ConnectionSettings.pausedBikes(settings, all))) {
            is Pairing.Appeared.Ignore -> Repo.log("display $mac appeared, not connecting: ${d.reason}")
            is Pairing.Appeared.Connect -> {
                if (bikes.active()?.id != d.bikeId) { bikes.setActive(d.bikeId); Repo.log("display $mac appeared, switched to bike ${d.bikeId}") }
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                Repo.log("display $mac appeared, starting connection")
                try { BikeService.start(this) } catch (e: Exception) { Repo.log("cannot start BikeService from background: $e") }
            }
        }
    }
}
