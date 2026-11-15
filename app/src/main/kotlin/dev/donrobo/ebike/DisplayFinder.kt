package dev.donrobo.ebike

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import dev.donrobo.ebike.pairing.Pairing
import dev.donrobo.ebike.settings.bikes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The active bike's display for [BikeService]'s next connection attempt. Known display (the active bike's MAC, set
 * by the companion association or an earlier scan): connected directly without scanning, also when not bonded yet
 * (the transport bonds after connecting). Otherwise a fallback scan, matched by name prefix.
 */
@SuppressLint("MissingPermission")
internal class DisplayFinder(private val ctx: Context) {
    suspend fun find(adapter: BluetoothAdapter): BluetoothDevice? {
        val bikes = ctx.bikes
        bikes.active()?.displayMac?.let { mac ->
            return if (Build.VERSION.SDK_INT >= 33) adapter.getRemoteLeDevice(mac, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
            else adapter.getRemoteDevice(mac)
        }
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Repo.log("BLUETOOTH_SCAN not granted, cannot search")
            return null
        }
        val scanner = adapter.bluetoothLeScanner ?: return null
        Repo.scanning()
        val found = CompletableDeferred<BluetoothDevice>()
        val seen = ConcurrentHashMap<String, String>() // address -> description, for the log
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: return
                // Known display name, and not another bike's display (A2)
                if (Pairing.isScanCandidate(name, r.device.address, bikes.all(), bikes.active()?.id)) found.complete(r.device)
                else seen[r.device.address] = "$name ${r.device.address} ${r.scanRecord?.serviceUuids.orEmpty()}"
            }
            override fun onScanFailed(errorCode: Int) { found.completeExceptionally(IllegalStateException("scan failed ($errorCode)")) }
        }
        // Unfiltered while the UI is visible (the display's advertisement is not known exactly); in the background
        // unfiltered scans are suppressed with the screen off, so fall back to filters there (OR'ed).
        // The display advertises its name with a trailing space and only the HID service (not the UART one it is
        // talked to over), so both filters come from Pairing; the callback still checks the name.
        val filters = if (appInForeground()) null else
            Pairing.SCAN_FILTER_NAMES.map { ScanFilter.Builder().setDeviceName(it).build() } +
                ScanFilter.Builder().setServiceUuid(ParcelUuid(Pairing.ADVERTISED_SERVICE)).build()
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        val dev = try { withTimeoutOrNull(SCAN_TIMEOUT_MS) { found.await() } } finally { runCatching { scanner.stopScan(cb) } }
        if (dev == null) {
            Repo.log("display not found (${if (filters == null) "unfiltered" else "filtered"} scan), saw ${seen.size} named device(s)")
            seen.values.forEach { Repo.log("  seen: $it") }
        } else {
            Repo.log("found ${dev.name} ${dev.address}")
            bikes.active()?.let { bikes.setMac(it.id, dev.address) }
        }
        return dev
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 15_000L
    }
}

/** An activity of the app is in the foreground (the UI is visible). */
fun appInForeground(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
    return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}
