package dev.donrobo.ebike.settings

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Whether a setting exists once for the app or once per [Bike]. */
enum class Scope { GLOBAL, PER_BIKE }

/**
 * A typed setting. Features declare their own keys next to their code, e.g.
 * `object LightSettings { val mode = Setting.enum("light.mode", LightMode.AUTO, Scope.PER_BIKE) }`,
 * so adding a setting never touches a central file. Per-bike keys are stored as `bike.<id>.<key>`.
 */
class Setting<T> private constructor(
    val key: String,
    val default: T,
    val scope: Scope,
    internal val read: (KeyValueStore, String, T) -> T,
    internal val write: KeyValueStore.Editor.(String, T) -> Unit,
) {
    companion object {
        fun bool(key: String, default: Boolean, scope: Scope = Scope.GLOBAL) =
            Setting(key, default, scope, { s, k, d -> s.getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

        fun int(key: String, default: Int, scope: Scope = Scope.GLOBAL) =
            Setting(key, default, scope, { s, k, d -> s.getInt(k, d) }, { k, v -> putInt(k, v) })

        fun long(key: String, default: Long, scope: Scope = Scope.GLOBAL) =
            Setting(key, default, scope, { s, k, d -> s.getLong(k, d) }, { k, v -> putLong(k, v) })

        fun double(key: String, default: Double, scope: Scope = Scope.GLOBAL) =
            Setting(key, default, scope, { s, k, d -> s.getDouble(k, d) }, { k, v -> putDouble(k, v) })

        /** Nullable string; null = not set (the key is removed). */
        fun string(key: String, scope: Scope = Scope.GLOBAL) =
            Setting<String?>(key, null, scope, { s, k, _ -> s.getString(k) }, { k, v -> putString(k, v) })

        fun string(key: String, default: String, scope: Scope = Scope.GLOBAL) =
            Setting(key, default, scope, { s, k, d -> s.getString(k) ?: d }, { k, v -> putString(k, v) })

        /** Enum stored by name; unknown names (e.g. a removed constant) fall back to [default]. */
        inline fun <reified E : Enum<E>> enum(key: String, default: E, scope: Scope = Scope.GLOBAL): Setting<E> =
            mapped(key, default, scope, { s -> enumValues<E>().firstOrNull { it.name == s } }, { it.name })

        /** Any value with a string codec. [decode] returns null for unreadable input, which yields [default]. */
        fun <T> mapped(key: String, default: T, scope: Scope, decode: (String) -> T?, encode: (T) -> String?) =
            Setting(key, default, scope, { s, k, d -> s.getString(k)?.let(decode) ?: d }, { k, v -> putString(k, encode(v)) })
    }
}

/**
 * The app's settings: one [KeyValueStore], typed access through [Setting] keys. Holds no cached
 * copies, so every read sees the stored value.
 */
class Settings(val store: KeyValueStore) {

    fun storageKey(s: Setting<*>, bikeId: String?): String = when (s.scope) {
        Scope.GLOBAL -> { require(bikeId == null) { "${s.key} is global" }; s.key }
        Scope.PER_BIKE -> "${bikePrefix(requireNotNull(bikeId) { "${s.key} is per bike" })}${s.key}"
    }

    operator fun <T> get(s: Setting<T>): T = get(s, null)
    fun <T> get(s: Setting<T>, bikeId: String?): T = s.read(store, storageKey(s, bikeId), s.default)

    operator fun <T> set(s: Setting<T>, value: T) = set(s, null, value)
    fun <T> set(s: Setting<T>, bikeId: String?, value: T) {
        val k = storageKey(s, bikeId)
        store.edit { s.write(this, k, value) }
    }

    fun reset(s: Setting<*>, bikeId: String? = null) = store.edit { remove(storageKey(s, bikeId)) }

    /** Current value, then every change. */
    fun <T> flow(s: Setting<T>, bikeId: String? = null): Flow<T> {
        val k = storageKey(s, bikeId)
        return store.changes().filter { it == k }.map { get(s, bikeId) }
            .onStart { emit(get(s, bikeId)) }.distinctUntilChanged()
    }

    /** Emits whenever any key changes (plus once at start); for derived data spanning several keys. */
    fun anyChange(): Flow<Unit> = store.changes().map { }.onStart { emit(Unit) }

    companion object {
        const val FILE = "ebike" // the pre-redesign Prefs file; kept so its keys stay where they are
        fun bikePrefix(bikeId: String) = "bike.$bikeId."

        @Volatile private var instance: Settings? = null

        /** Process-wide instance (one SharedPreferences file). */
        fun get(ctx: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(SharedPrefsStore(ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)))
                .also { instance = it }
        }
    }
}

val Context.settings: Settings get() = Settings.get(this)
