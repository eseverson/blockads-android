package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.data.entities.WireGuardProfileList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WireGuard settings. Profiles hold private and preshared keys, so they live in [secretsStore], a separate file that
 * backup_rules.xml and data_extraction_rules.xml exclude; everything else stays in the shared settings [dataStore].
 * A restore therefore brings back the routing mode without any profile, and the user has to re-import.
 */
class WireGuardPreferences(
    private val dataStore: DataStore<Preferences>,
    private val secretsStore: DataStore<Preferences>,
) {

    companion object {
        val KEY_ROUTING_MODE = stringPreferencesKey("routing_mode")
        val KEY_WG_CONFIG_JSON = stringPreferencesKey("wg_config_json")
        val KEY_WG_PROFILES_JSON = stringPreferencesKey("wg_profiles_json")
        val KEY_WG_ACTIVE_PROFILE_ID = stringPreferencesKey("wg_active_profile_id")
        val KEY_EXCLUDE_LAN = booleanPreferencesKey("exclude_lan")
        val KEY_ALLOW_APP_BYPASS = booleanPreferencesKey("allow_app_bypass")

        const val ROUTING_MODE_DIRECT = "direct"
        const val ROUTING_MODE_WIREGUARD = "wireguard"
        const val ROUTING_MODE_ROOT = "root"

        private const val LEGACY_PROFILE_ID = "legacy-default"

        private val SECRET_KEYS = listOf(KEY_WG_CONFIG_JSON, KEY_WG_PROFILES_JSON, KEY_WG_ACTIVE_PROFILE_ID)
    }

    private val secretsMoveLock = Mutex()

    @Volatile
    private var secretsMoved = false

    /** [secretsStore]'s data, after profiles written by older versions have been moved out of [dataStore]. */
    private val secretsData: Flow<Preferences> = flow {
        moveSecretsOutOfSharedStore()
        emitAll(secretsStore.data)
    }

    val routingMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_ROUTING_MODE] ?: ROUTING_MODE_DIRECT
    }

    val wgProfiles: Flow<List<WireGuardProfile>> = secretsData.map { prefs ->
        readProfilesFromPrefs(prefs)
    }

    val wgActiveProfileId: Flow<String?> = secretsData.map { prefs ->
        readActiveIdFromPrefs(prefs)
    }

    val excludeLan: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_EXCLUDE_LAN] ?: false
    }

    val allowAppBypass: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ALLOW_APP_BYPASS] ?: false
    }

    /**
     * Older versions kept the profiles in the shared settings file, which auto-backup copies. Copy them over unless
     * [secretsStore] already has profiles (an earlier move got that far), then drop them from the shared file.
     */
    private suspend fun moveSecretsOutOfSharedStore() {
        if (secretsMoved) return
        secretsMoveLock.withLock {
            if (secretsMoved) return
            val shared = dataStore.data.first()
            if (SECRET_KEYS.any { shared.contains(it) }) {
                secretsStore.edit { secrets ->
                    if (secrets[KEY_WG_PROFILES_JSON] == null && secrets[KEY_WG_CONFIG_JSON] == null) {
                        shared[KEY_WG_PROFILES_JSON]?.let { secrets[KEY_WG_PROFILES_JSON] = it }
                        shared[KEY_WG_CONFIG_JSON]?.let { secrets[KEY_WG_CONFIG_JSON] = it }
                        shared[KEY_WG_ACTIVE_PROFILE_ID]?.let { secrets[KEY_WG_ACTIVE_PROFILE_ID] = it }
                    }
                }
                dataStore.edit { prefs -> SECRET_KEYS.forEach { prefs.remove(it) } }
            }
            secretsMoved = true
        }
    }

    private suspend fun editSecrets(transform: suspend (MutablePreferences) -> Unit) {
        moveSecretsOutOfSharedStore()
        secretsStore.edit(transform)
    }

    private fun readProfilesFromPrefs(prefs: Preferences): List<WireGuardProfile> {
        prefs[KEY_WG_PROFILES_JSON]?.let {
            return WireGuardProfileList.fromJson(it).profiles
        }
        prefs[KEY_WG_CONFIG_JSON]?.let { legacy ->
            val cfg = try {
                WireGuardConfig.fromJson(legacy)
            } catch (_: Exception) {
                return emptyList()
            }
            return listOf(WireGuardProfile(LEGACY_PROFILE_ID, "Default", cfg))
        }
        return emptyList()
    }

    private fun readActiveIdFromPrefs(prefs: Preferences): String? {
        prefs[KEY_WG_ACTIVE_PROFILE_ID]?.let { return it }
        if (prefs[KEY_WG_CONFIG_JSON] != null) return LEGACY_PROFILE_ID
        return null
    }

    suspend fun setRoutingMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_ROUTING_MODE] = mode
        }
    }

    suspend fun getRoutingModeSnapshot(): String {
        return routingMode.first()
    }

    suspend fun getWgConfigJsonSnapshot(): String? {
        val active = getActiveWgProfileSnapshot() ?: return null
        return active.config.toJson()
    }

    suspend fun getWgProfilesSnapshot(): List<WireGuardProfile> = wgProfiles.first()

    suspend fun getActiveWgProfileSnapshot(): WireGuardProfile? {
        val profiles = getWgProfilesSnapshot()
        if (profiles.isEmpty()) return null
        val activeId = wgActiveProfileId.first()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    suspend fun addOrUpdateWgProfile(profile: WireGuardProfile, makeActive: Boolean = false) {
        editSecrets { prefs ->
            val current = readProfilesFromPrefs(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) current[idx] = profile else current.add(profile)
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(current).toJson()
            if (makeActive) prefs[KEY_WG_ACTIVE_PROFILE_ID] = profile.id
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun removeWgProfile(id: String) {
        editSecrets { prefs ->
            val current = readProfilesFromPrefs(prefs).filterNot { it.id == id }
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(current).toJson()
            if (prefs[KEY_WG_ACTIVE_PROFILE_ID] == id) {
                val newActive = current.firstOrNull()?.id
                if (newActive != null) {
                    prefs[KEY_WG_ACTIVE_PROFILE_ID] = newActive
                } else {
                    prefs.remove(KEY_WG_ACTIVE_PROFILE_ID)
                }
            }
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun setActiveWgProfile(id: String) {
        editSecrets { prefs ->
            prefs[KEY_WG_ACTIVE_PROFILE_ID] = id
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun renameWgProfile(id: String, name: String) {
        editSecrets { prefs ->
            val current = readProfilesFromPrefs(prefs).map {
                if (it.id == id) it.copy(name = name) else it
            }
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(current).toJson()
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun clearAllWgProfiles() {
        editSecrets { prefs ->
            prefs.remove(KEY_WG_PROFILES_JSON)
            prefs.remove(KEY_WG_ACTIVE_PROFILE_ID)
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun migrateLegacyWgConfigIfNeeded() {
        editSecrets { prefs ->
            if (prefs[KEY_WG_PROFILES_JSON] != null) {
                prefs.remove(KEY_WG_CONFIG_JSON)
                return@editSecrets
            }
            val legacyJson = prefs[KEY_WG_CONFIG_JSON] ?: return@editSecrets
            val cfg = try {
                WireGuardConfig.fromJson(legacyJson)
            } catch (_: Exception) {
                prefs.remove(KEY_WG_CONFIG_JSON)
                return@editSecrets
            }
            val profile = WireGuardProfile(LEGACY_PROFILE_ID, "Default", cfg)
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(listOf(profile)).toJson()
            prefs[KEY_WG_ACTIVE_PROFILE_ID] = profile.id
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun setExcludeLan(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_EXCLUDE_LAN] = enabled
        }
    }

    suspend fun setAllowAppBypass(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ALLOW_APP_BYPASS] = enabled
        }
    }
}
