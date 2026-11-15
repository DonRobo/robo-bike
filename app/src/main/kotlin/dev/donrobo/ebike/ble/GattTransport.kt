package dev.donrobo.ebike.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import dev.donrobo.ebike.Repo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The byte pipe under [Ekd01Link]: frames go out with [write], notified bytes come back through the `onData`
 * callback given to [open]. Production uses [GattTransport]; debug builds can plug in a simulated display.
 */
interface LinkTransport {
    /** Connects and enables notifications. [onData] receives notified chunks, [onClosed] the loss of the link. */
    suspend fun open(onData: (ByteArray) -> Unit, onClosed: () -> Unit)

    /** Writes one whole frame (the transport splits it if it has to). Throws when the link is gone or the write fails. */
    suspend fun write(frame: ByteArray)

    /** Disconnects; [open]'s onClosed is not called for this. Safe to call more than once. */
    fun close()
}

/**
 * One GATT connection to the EKD01 display (Nordic UART): connect, MTU, service discovery, bonding, notifications
 * (sequence of the BikeGo app). All GATT operations are serialised through [lock].
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class GattTransport(private val ctx: Context, private val device: BluetoothDevice) : LinkTransport {
    private val lock = Mutex()
    private val connected = CompletableDeferred<Unit>()
    /** Completes once the GATT link is gone (remote disconnect, failure or [close]). */
    private val gone = CompletableDeferred<Unit>()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var onData: (ByteArray) -> Unit = {}
    @Volatile private var onClosed: () -> Unit = {}
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) connected.complete(Unit)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Repo.log("BLE disconnected (status $status)")
                markGone()
                g.close()
                onClosed()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@GattTransport.mtu = mtu
            pending?.complete(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { pending?.complete(status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { pending?.complete(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { pending?.complete(status) }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            onData(c.value ?: return)
        }
    }

    override suspend fun open(onData: (ByteArray) -> Unit, onClosed: () -> Unit) {
        this.onData = onData
        this.onClosed = onClosed
        Repo.log("connecting to ${device.address}")
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) ?: throw Ekd01Exception("connectGatt failed")
        withTimeoutOrNull(20_000) { connected.await() } ?: throw Ekd01Exception("connect timeout")
        val g = gatt!!
        val mtuStatus = runCatching { lock.withLock { op(3_000) { g.requestMtu(512) } } }
        Repo.log("MTU $mtu (${mtuStatus.exceptionOrNull()?.message ?: "status ${mtuStatus.getOrNull()}"})")
        delay(500)
        val ds = lock.withLock { op(10_000) { g.discoverServices() } }
        if (ds != BluetoothGatt.GATT_SUCCESS) throw Ekd01Exception("service discovery failed ($ds)")
        val svc = g.getService(SERVICE) ?: throw Ekd01Exception("UART service missing")
        tx = svc.getCharacteristic(TX_CHAR) ?: throw Ekd01Exception("write characteristic missing")
        val rx = svc.getCharacteristic(RX_CHAR) ?: throw Ekd01Exception("notify characteristic missing")
        ensureBonded()
        enableNotify(g, rx)
    }

    private suspend fun ensureBonded() {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Repo.log("bonding (Just Works)")
            if (!device.createBond()) throw Ekd01Exception("createBond refused")
        }
        var seenBonding = false
        withTimeoutOrNull(30_000) {
            while (device.bondState != BluetoothDevice.BOND_BONDED) {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDING -> seenBonding = true
                    BluetoothDevice.BOND_NONE -> if (seenBonding) throw Ekd01Exception("bonding failed")
                }
                if (gone.isCompleted) throw Ekd01Exception("disconnected while bonding")
                delay(250)
            }
            Repo.log("bonded")
        } ?: throw Ekd01Exception("bonding timeout")
    }

    private suspend fun enableNotify(g: BluetoothGatt, rx: BluetoothGattCharacteristic) {
        val cccd = rx.getDescriptor(CCCD) ?: throw Ekd01Exception("CCCD missing")
        for (attempt in 1..4) {
            if (!g.setCharacteristicNotification(rx, true)) throw Ekd01Exception("setCharacteristicNotification failed")
            val status = try {
                lock.withLock { op(5_000) { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) } }
            } catch (e: Ekd01Exception) {
                if (gone.isCompleted) throw e
                -1
            }
            if (status == BluetoothGatt.GATT_SUCCESS) return
            Repo.log("notify attempt $attempt failed (status $status)")
            if (status in AUTH_ERRORS || device.bondState != BluetoothDevice.BOND_BONDED) ensureBonded() else delay(500)
        }
        throw Ekd01Exception("cannot enable notifications")
    }

    override fun close() {
        if (gone.isCompleted) return
        gatt?.let { it.disconnect(); it.close() }
        markGone()
    }

    private fun markGone() {
        val e = Ekd01Exception("disconnected")
        connected.completeExceptionally(e)
        pending?.completeExceptionally(e)
        gone.complete(Unit)
    }

    /** Starts one GATT operation and waits for its callback status. Caller must hold [lock]. */
    private suspend fun op(timeoutMs: Long, start: () -> Boolean): Int {
        if (gone.isCompleted) throw Ekd01Exception("disconnected")
        val d = CompletableDeferred<Int>()
        pending = d
        try {
            if (!start()) throw Ekd01Exception("GATT operation refused")
            return withTimeoutOrNull(timeoutMs) { d.await() } ?: throw Ekd01Exception("GATT operation timeout")
        } finally {
            pending = null
        }
    }

    /** Write with response, chunked to MTU-3. */
    override suspend fun write(frame: ByteArray) {
        val g = gatt ?: throw Ekd01Exception("not connected")
        val c = tx ?: throw Ekd01Exception("not connected")
        lock.withLock {
            for (chunk in frame.asList().chunked(mtu - 3)) {
                val status = op(3_000) {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    c.value = chunk.toByteArray()
                    g.writeCharacteristic(c)
                }
                if (status != BluetoothGatt.GATT_SUCCESS) throw Ekd01Exception("write failed ($status)")
            }
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val TX_CHAR: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_CHAR: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Insufficient authentication / encryption, GATT_AUTH_FAIL. */
        private val AUTH_ERRORS = setOf(0x05, 0x0F, 0x89)
    }
}
