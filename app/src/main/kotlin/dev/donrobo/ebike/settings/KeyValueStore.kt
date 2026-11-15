package dev.donrobo.ebike.settings

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Typed key-value persistence under [Settings]. The production implementation is SharedPreferences;
 * [MemoryStore] backs the JVM unit tests. Values keep their stored type (the pre-redesign app wrote
 * booleans and strings into the same file, and those keys are still read as they are).
 */
interface KeyValueStore {
    fun contains(key: String): Boolean
    fun keys(): Set<String>
    /**
     * Every stored value with its stored type (Boolean, Int, Long, Double, String; SharedPreferences hold
     * doubles as Long bits, see [SharedPrefsStore]). For backups; values of other types are left out.
     */
    fun all(): Map<String, Any>
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getDouble(key: String, default: Double): Double
    fun getString(key: String): String?
    /**
     * Applies all writes of [block] together. A null value removes the key. [sync] = written to disk
     * before returning (migrations); otherwise asynchronously (SharedPreferences.apply).
     */
    fun edit(sync: Boolean = false, block: Editor.() -> Unit)
    /** Emits the changed key after each write (no initial emission). */
    fun changes(): Flow<String>

    interface Editor {
        fun putBoolean(key: String, v: Boolean)
        fun putInt(key: String, v: Int)
        fun putLong(key: String, v: Long)
        fun putDouble(key: String, v: Double)
        fun putString(key: String, v: String?)
        fun remove(key: String)
    }
}

class SharedPrefsStore(private val sp: SharedPreferences) : KeyValueStore {
    override fun contains(key: String) = sp.contains(key)
    override fun keys(): Set<String> = sp.all.keys
    override fun all(): Map<String, Any> = sp.all.filterValues { it is Boolean || it is Int || it is Long || it is String }.mapValues { it.value!! }
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = sp.getInt(key, default)
    override fun getLong(key: String, default: Long) = sp.getLong(key, default)
    // Doubles are stored as their raw long bits: SharedPreferences has no double type.
    override fun getDouble(key: String, default: Double) =
        if (sp.contains(key)) Double.fromBits(sp.getLong(key, 0)) else default
    override fun getString(key: String): String? = sp.getString(key, null)

    override fun edit(sync: Boolean, block: KeyValueStore.Editor.() -> Unit) {
        val e = sp.edit()
        object : KeyValueStore.Editor {
            override fun putBoolean(key: String, v: Boolean) { e.putBoolean(key, v) }
            override fun putInt(key: String, v: Int) { e.putInt(key, v) }
            override fun putLong(key: String, v: Long) { e.putLong(key, v) }
            override fun putDouble(key: String, v: Double) { e.putLong(key, v.toRawBits()) }
            override fun putString(key: String, v: String?) { if (v == null) e.remove(key) else e.putString(key, v) }
            override fun remove(key: String) { e.remove(key) }
        }.block()
        if (sync) e.commit() else e.apply()
    }

    override fun changes(): Flow<String> = callbackFlow {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null) trySend(key) }
        sp.registerOnSharedPreferenceChangeListener(l)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
}

/** In-memory store for tests and previews. */
class MemoryStore(initial: Map<String, Any> = emptyMap()) : KeyValueStore {
    private val map = ConcurrentHashMap<String, Any>(initial)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    override fun contains(key: String) = map.containsKey(key)
    override fun keys(): Set<String> = map.keys.toSet()
    override fun all(): Map<String, Any> = map.toMap()
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun getLong(key: String, default: Long) = map[key] as? Long ?: default
    override fun getDouble(key: String, default: Double) = map[key] as? Double ?: default
    override fun getString(key: String) = map[key] as? String

    override fun edit(sync: Boolean, block: KeyValueStore.Editor.() -> Unit) {
        val changed = mutableListOf<String>()
        fun put(k: String, v: Any?) { if (v == null) map.remove(k) else map[k] = v; changed += k }
        object : KeyValueStore.Editor {
            override fun putBoolean(key: String, v: Boolean) = put(key, v)
            override fun putInt(key: String, v: Int) = put(key, v)
            override fun putLong(key: String, v: Long) = put(key, v)
            override fun putDouble(key: String, v: Double) = put(key, v)
            override fun putString(key: String, v: String?) = put(key, v)
            override fun remove(key: String) = put(key, null)
        }.block()
        changed.forEach { k -> listeners.forEach { it(k) } }
    }

    override fun changes(): Flow<String> = callbackFlow {
        val l: (String) -> Unit = { trySend(it) }
        listeners += l
        awaitClose { listeners -= l }
    }
}
