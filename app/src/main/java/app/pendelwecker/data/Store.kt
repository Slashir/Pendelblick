package app.pendelwecker.data

import android.content.Context
import androidx.core.content.edit
import app.pendelwecker.domain.CommuteProfile
import app.pendelwecker.domain.StatusSnapshot
import kotlinx.serialization.builtins.ListSerializer

/** Speichert Pendelprofile und den letzten Prüfstand lokal auf dem Gerät. Kein Server, kein Konto. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pendelwecker", Context.MODE_PRIVATE)
    private val json = TransitousApi.json

    fun profiles(): List<CommuteProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return CommuteProfile.defaults()
        return runCatching { json.decodeFromString(ListSerializer(CommuteProfile.serializer()), raw) }.getOrElse {
            // Nicht lesbar (z. B. nach einer Formatänderung): Rohdaten aufheben, statt sie beim nächsten
            // Speichern mit den Vorgaben zu überschreiben.
            if (!prefs.contains(KEY_PROFILES_BROKEN)) prefs.edit { putString(KEY_PROFILES_BROKEN, raw) }
            CommuteProfile.defaults()
        }
    }

    fun saveProfile(profile: CommuteProfile) {
        val list = profiles().map { if (it.id == profile.id) profile else it }
        prefs.edit { putString(KEY_PROFILES, json.encodeToString(ListSerializer(CommuteProfile.serializer()), list)) }
    }

    fun snapshots(): List<StatusSnapshot> =
        prefs.getString(KEY_SNAPSHOTS, null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(StatusSnapshot.serializer()), it) }.getOrNull() }
            ?: emptyList()

    fun saveSnapshot(s: StatusSnapshot) {
        val list = snapshots().filterNot { it.profileId == s.profileId } + s
        prefs.edit { putString(KEY_SNAPSHOTS, json.encodeToString(ListSerializer(StatusSnapshot.serializer()), list)) }
    }

    fun removeSnapshot(profileId: Int) {
        val list = snapshots().filterNot { it.profileId == profileId }
        prefs.edit { putString(KEY_SNAPSHOTS, json.encodeToString(ListSerializer(StatusSnapshot.serializer()), list)) }
    }

    /** Zuletzt erledigter Prüfzeitpunkt je Fahrt (Epoche in ms); früher oder gleich wird nicht neu geplant. */
    fun lastHandledCheck(profileId: Int): Long = prefs.getLong(KEY_HANDLED + profileId, 0)

    fun markHandled(profileId: Int, atEpochMs: Long) {
        if (atEpochMs > lastHandledCheck(profileId)) prefs.edit { putLong(KEY_HANDLED + profileId, atEpochMs) }
    }

    /**
     * Merkt sich, ob der Server den Parameter realtimeMode ablehnt (ältere MOTIS-Version). Nach einer
     * Woche wird es erneut versucht, falls der Server inzwischen aktualisiert wurde.
     */
    var realtimeModeSupported: Boolean
        get() = !fallbackActive(KEY_RT_MODE) || prefs.getBoolean(KEY_RT_MODE, true)
        set(v) = prefs.edit { putBoolean(KEY_RT_MODE, v); putLong(KEY_RT_MODE + SINCE, System.currentTimeMillis()) }

    /** Funktionierende API-Version; nach einer Woche wird wieder die Vorgabe probiert. */
    var apiVersion: String
        get() = if (fallbackActive(KEY_API_VERSION)) prefs.getString(KEY_API_VERSION, DEFAULT_VERSION) ?: DEFAULT_VERSION else DEFAULT_VERSION
        set(v) = prefs.edit { putString(KEY_API_VERSION, v); putLong(KEY_API_VERSION + SINCE, System.currentTimeMillis()) }

    private fun fallbackActive(key: String): Boolean =
        System.currentTimeMillis() - prefs.getLong(key + SINCE, 0) < FALLBACK_TTL_MS

    /** Live-Karte: nur die Linien der eigenen Fahrten zeigen. */
    var mapOnlyMine: Boolean
        get() = prefs.getBoolean(KEY_MAP_ONLY_MINE, false)
        set(v) = prefs.edit { putBoolean(KEY_MAP_ONLY_MINE, v) }

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_SNAPSHOTS = "snapshots"
        const val KEY_RT_MODE = "realtime_mode_supported"
        const val KEY_API_VERSION = "api_version"
        const val KEY_MAP_ONLY_MINE = "map_only_mine"
        const val KEY_HANDLED = "handled_"
        const val KEY_PROFILES_BROKEN = "profiles_unreadable"
        const val SINCE = "_since"
        const val DEFAULT_VERSION = "v5"
        const val FALLBACK_TTL_MS = 7L * 24 * 3600 * 1000
    }
}
