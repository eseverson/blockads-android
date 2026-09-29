package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardInterface
import app.pwhs.blockads.data.entities.WireGuardPeer
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.data.entities.WireGuardProfileList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WireGuardPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val shared: DataStore<Preferences> by lazy { newStore("blockads_prefs") }
    private val secrets: DataStore<Preferences> by lazy { newStore("blockads_wg_secrets") }

    private fun newStore(name: String): DataStore<Preferences> {
        val file = File(tempFolder.root, "$name.preferences_pb")
        return PreferenceDataStoreFactory.create { file }
    }

    private fun newPreferences() = WireGuardPreferences(shared, secrets)

    private val profile = WireGuardProfile(
        id = "p1",
        name = "Home",
        config = WireGuardConfig(
            interfaceConfig = WireGuardInterface(privateKey = "private-key", address = listOf("10.0.0.2/32")),
            peers = listOf(WireGuardPeer(publicKey = "public-key", presharedKey = "preshared-key")),
        ),
    )

    @Test
    fun `allowAppBypass stays opt-in so WireGuard mode starts non-bypassable`() = runTest {
        assertFalse(newPreferences().allowAppBypass.first())
    }

    @Test
    fun `allowAppBypass keeps an explicit opt-in`() = runTest {
        val prefs = newPreferences()
        prefs.setAllowAppBypass(true)
        assertTrue(prefs.allowAppBypass.first())
    }

    @Test
    fun `excludeLan stays opt-in`() = runTest {
        assertFalse(newPreferences().excludeLan.first())
    }

    @Test
    fun `profiles are written to the secrets store, not the backed-up settings file`() = runTest {
        val prefs = newPreferences()
        prefs.setRoutingMode(WireGuardPreferences.ROUTING_MODE_WIREGUARD)
        prefs.addOrUpdateWgProfile(profile, makeActive = true)

        assertEquals(listOf(profile), prefs.getWgProfilesSnapshot())
        val sharedPrefs = shared.data.first()
        assertNull(sharedPrefs[WireGuardPreferences.KEY_WG_PROFILES_JSON])
        assertNull(sharedPrefs[WireGuardPreferences.KEY_WG_ACTIVE_PROFILE_ID])
        assertEquals(WireGuardPreferences.ROUTING_MODE_WIREGUARD, sharedPrefs[WireGuardPreferences.KEY_ROUTING_MODE])
        assertEquals("p1", secrets.data.first()[WireGuardPreferences.KEY_WG_ACTIVE_PROFILE_ID])
    }

    @Test
    fun `profiles stored by older versions move out of the settings file on first read`() = runTest {
        shared.edit {
            it[WireGuardPreferences.KEY_WG_PROFILES_JSON] = WireGuardProfileList(listOf(profile)).toJson()
            it[WireGuardPreferences.KEY_WG_ACTIVE_PROFILE_ID] = "p1"
            it[WireGuardPreferences.KEY_ROUTING_MODE] = WireGuardPreferences.ROUTING_MODE_WIREGUARD
        }

        val prefs = newPreferences()

        assertEquals(profile, prefs.getActiveWgProfileSnapshot())
        val sharedPrefs = shared.data.first()
        assertNull(sharedPrefs[WireGuardPreferences.KEY_WG_PROFILES_JSON])
        assertNull(sharedPrefs[WireGuardPreferences.KEY_WG_ACTIVE_PROFILE_ID])
        assertEquals(WireGuardPreferences.ROUTING_MODE_WIREGUARD, prefs.getRoutingModeSnapshot())
    }

    @Test
    fun `a legacy single config moves out of the settings file and migrates to a profile`() = runTest {
        shared.edit { it[WireGuardPreferences.KEY_WG_CONFIG_JSON] = profile.config.toJson() }

        val prefs = newPreferences()
        prefs.migrateLegacyWgConfigIfNeeded()

        assertEquals(profile.config, prefs.getActiveWgProfileSnapshot()?.config)
        assertNull(shared.data.first()[WireGuardPreferences.KEY_WG_CONFIG_JSON])
        assertNull(secrets.data.first()[WireGuardPreferences.KEY_WG_CONFIG_JSON])
    }

    @Test
    fun `an interrupted move keeps the copy already in the secrets store`() = runTest {
        val newer = profile.copy(name = "Newer")
        secrets.edit { it[WireGuardPreferences.KEY_WG_PROFILES_JSON] = WireGuardProfileList(listOf(newer)).toJson() }
        shared.edit { it[WireGuardPreferences.KEY_WG_PROFILES_JSON] = WireGuardProfileList(listOf(profile)).toJson() }

        val prefs = newPreferences()

        assertEquals(listOf(newer), prefs.getWgProfilesSnapshot())
        assertNull(shared.data.first()[WireGuardPreferences.KEY_WG_PROFILES_JSON])
    }

    @Test
    fun `a restore without the secrets file keeps WireGuard mode but has no profile to connect with`() = runTest {
        shared.edit { it[WireGuardPreferences.KEY_ROUTING_MODE] = WireGuardPreferences.ROUTING_MODE_WIREGUARD }

        val prefs = newPreferences()

        assertEquals(WireGuardPreferences.ROUTING_MODE_WIREGUARD, prefs.getRoutingModeSnapshot())
        assertTrue(prefs.getWgProfilesSnapshot().isEmpty())
        assertNull(prefs.getWgConfigJsonSnapshot())
    }
}
