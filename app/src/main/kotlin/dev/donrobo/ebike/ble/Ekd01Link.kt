package dev.donrobo.ebike.ble

import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.proto.Dev
import dev.donrobo.ebike.proto.Frame
import dev.donrobo.ebike.proto.FrameParser
import dev.donrobo.ebike.proto.Op
import dev.donrobo.ebike.proto.authResponse
import dev.donrobo.ebike.proto.buildFrame
import dev.donrobo.ebike.proto.hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class Ekd01Exception(msg: String) : IOException(msg)

/**
 * The EKD01 protocol over one connection to the display: authentication, framing, and
 * requests matched to their responses by (src, op, reg). The bytes travel over a [LinkTransport]: the GATT link
 * ([GattTransport]), or in debug builds the simulated display.
 *
 * A response carries nothing but (src, op, reg) to tell which request it answers, so requests with the same key
 * take turns ([keyLocks]); requests to different registers run concurrently.
 */
class Ekd01Link(
    private val transport: LinkTransport,
    private val onReport: (reg: Int, data: ByteArray) -> Unit,
) {
    /** Completes once the link is gone (remote disconnect, failure or [close]). */
    val disconnected = CompletableDeferred<Unit>()

    private val waiters = ConcurrentHashMap<Triple<Int, Int, Int>, CompletableDeferred<Frame>>()
    /** One lock per response key: a second request to the same register waits for the first one's answer. */
    private val keyLocks = ConcurrentHashMap<Triple<Int, Int, Int>, Mutex>()
    private val parser = FrameParser()
    private val closed = AtomicBoolean(false)

    private fun onData(chunk: ByteArray) {
        val frames = synchronized(parser) {
            try { parser.feed(chunk) } catch (e: IllegalArgumentException) { Repo.log("RX garbage: ${e.message}"); emptyList() }
        }
        frames.forEach(::onFrame)
    }

    private fun onFrame(fr: Frame) {
        if (!fr.checksumOk) { Repo.log("RX bad checksum, dropped: $fr"); return }
        if (fr.op == Op.REPORT) {
            try { onReport(fr.reg, fr.data) } catch (e: Exception) { Repo.log("report ${fr.reg} handler: $e") }
            return
        }
        Repo.log("RX $fr")
        waiters.remove(Triple(fr.src, fr.op, fr.reg))?.complete(fr)
    }

    // --- connection ---------------------------------------------------------

    /** Connect (transport: GATT, bonding, notifications), then authenticate (sequence of the BikeGo app). */
    suspend fun connect() {
        transport.open(::onData, ::shutdown)
        delay(500)
        authenticate()
        Repo.log("authenticated")
    }

    private suspend fun authenticate() {
        repeat(5) {
            try {
                val challenge = read(Dev.DISPLAY, 0, 4)
                val reply = request(Dev.DISPLAY, Op.AUTH, 0, authResponse(challenge), Op.AUTH, 2_000)
                if (reply.data.firstOrNull() == 0.toByte()) return
                Repo.log("auth rejected: ${reply.data.hex()}")
            } catch (e: Exception) {
                if (disconnected.isCompleted) throw e
                Repo.log("auth attempt failed: ${e.message}")
            }
        }
        throw Ekd01Exception("authentication failed")
    }

    /** Disconnect and release the transport. Safe to call more than once. */
    fun close() {
        if (closed.getAndSet(true)) return
        transport.close()
        shutdown()
    }

    private fun shutdown() {
        closed.set(true)
        val e = Ekd01Exception("disconnected")
        waiters.values.forEach { it.completeExceptionally(e) }
        waiters.clear()
        disconnected.complete(Unit)
    }

    // --- public API ---------------------------------------------------------

    /** Writes one frame. [log] = false for chatty periodic frames. */
    suspend fun send(raw: ByteArray, log: Boolean = true) {
        if (log) Repo.log("TX ${Frame.parse(raw)}")
        if (disconnected.isCompleted) throw Ekd01Exception("disconnected")
        transport.write(raw)
    }

    /** Sends one request and waits for its response; [timeoutMs] counts from the send, not from waiting for the key. */
    suspend fun request(dst: Int, op: Int, reg: Int, data: ByteArray, respOp: Int, timeoutMs: Long = 1_500): Frame {
        val key = Triple(dst, respOp, reg)
        return keyLocks.computeIfAbsent(key) { Mutex() }.withLock {
            val w = CompletableDeferred<Frame>()
            waiters[key] = w
            try {
                send(buildFrame(dst, op, reg, data))
                withTimeoutOrNull(timeoutMs) { w.await() }
                    ?: throw Ekd01Exception("no response from %02x reg %d (op %02x)".format(dst, reg, op))
            } finally {
                waiters.remove(key, w)
            }
        }
    }

    /**
     * Register read of [len] bytes. The firmware may answer with more than requested (its own register map
     * differs from the app's: `0xA5:120` asked for 16 returns 32, the rest belongs to the next register),
     * so a longer answer is cut to [len]; a shorter one is returned as it is.
     */
    suspend fun read(target: Int, reg: Int, len: Int): ByteArray {
        val data = readFull(target, reg, len)
        return if (data.size > len) data.copyOf(len) else data
    }

    /** Register read asking for [len] bytes, returning the answer exactly as the display sent it (diagnostics). */
    suspend fun readFull(target: Int, reg: Int, len: Int): ByteArray =
        request(target, Op.READ, reg, byteArrayOf(len.toByte()), Op.READ_RESP).data

    /** Register write; DATA must have exactly the register's length. Throws if the display rejects it. */
    suspend fun write(target: Int, reg: Int, data: ByteArray) {
        val ack = request(target, Op.WRITE, reg, data, Op.WRITE_ACK)
        if (ack.data.firstOrNull() != 0.toByte()) throw Ekd01Exception("write %02x:%d rejected, status %s".format(target, reg, ack.data.hex()))
    }

    companion object {
        val SERVICE: UUID = GattTransport.SERVICE
    }
}
