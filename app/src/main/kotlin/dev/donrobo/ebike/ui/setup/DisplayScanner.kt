package dev.donrobo.ebike.ui.setup

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.pairing.SeenDevice

/**
 * The setup's own BLE scan (step 1), used while the screen is visible: unfiltered, because the display advertises
 * only a name (`EKD01-BF `) and the HID service; [dev.donrobo.ebike.pairing.pickDisplay] picks the display.
 * Needs BLUETOOTH_SCAN (declared neverForLocation, so no location permission).
 */
@SuppressLint("MissingPermission")
class DisplayScanner(private val ctx: Context) {
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var callback: ScanCallback? = null

    fun bluetoothOn(): Boolean = adapter?.isEnabled == true

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    /** Starts scanning; [onSeen] is called on a binder thread for every named advertisement. False if not possible. */
    fun start(onSeen: (SeenDevice) -> Unit): Boolean {
        stop()
        if (!hasPermission() || !bluetoothOn()) return false
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: return
                onSeen(SeenDevice(r.device.address.uppercase(), name, r.rssi))
            }
            override fun onScanFailed(errorCode: Int) { Repo.log("setup scan failed ($errorCode)") }
        }
        return try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            callback = cb
            true
        } catch (e: Exception) {
            Repo.log("setup scan: $e")
            false
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}
