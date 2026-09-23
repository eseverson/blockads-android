package app.pwhs.blockads.service

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.ui.browser.waitUntil
import app.pwhs.blockads.ui.customrules.CustomRulesViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Starts the real VPN on an emulator and checks what other apps see.
 *
 * The app excludes its own package from the tunnel, and the instrumentation shares its UID, so lookups made
 * from this process would bypass the VPN. They are made from the shell UID instead, which the tunnel does cover.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class VpnSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val customRules: CustomDnsRuleDao = getKoin().get()
    private val prefs: AppPreferences = getKoin().get()
    private val dnsLogs: DnsLogDao = getKoin().get()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var savedWhitelist: Set<String> = emptySet()

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun awaitHomeText(@StringRes id: Int) {
        val text = context.getString(id)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /** The address the shell UID's resolver returns for [host], or null if it does not resolve. */
    private fun resolve(host: String): String? =
        Regex("""PING \S+ \(([^)]+)\)""").find(shell("ping -c 1 -W 1 $host"))?.groupValues?.get(1)

    /** iputils ping reports a 0.0.0.0 answer as 127.0.0.1, so both count as the sinkhole. */
    private fun sinkholed(host: String) = resolve(host) in setOf(SINKHOLE, "127.0.0.1")

    private fun resolvesNormally(host: String) = resolve(host).let { it != null && it != SINKHOLE && it != "127.0.0.1" }

    private fun loggedAsBlocked(host: String, since: Long): DnsLogEntry? =
        runBlocking { dnsLogs.getBlockedOnlySince(since).first() }.firstOrNull { it.domain == host }

    private fun vpnTransportUp(): Boolean = "ni{VPN CONNECTED" in shell("dumpsys connectivity")

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        assumeTrue("VPN consent could not be pre-granted", VpnService.prepare(context) == null)
        runBlocking {
            savedWhitelist = prefs.getWhitelistedAppsSnapshot()
            prefs.setOnboardingCompleted(true)
            customRules.insert(CustomDnsRule(rule = "||$BLOCKED^", ruleType = RuleType.BLOCK, domain = BLOCKED))
        }
        assumeTrue("the emulator needs working DNS", resolve(CLEAN) != null)
        // The app must be in the foreground to start its service on API 31+.
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        AdBlockVpnService.stop(context)
        waitUntil("the VPN to stop", 15_000) { AdBlockVpnService.state.value == VpnState.STOPPED }
        runBlocking {
            customRules.getAll().filter { it.domain == BLOCKED }.forEach { customRules.delete(it) }
            prefs.setWhitelistedApps(savedWhitelist)
        }
        scenario?.close()
    }

    @Test
    fun startBlocksACustomRuleDomainForOtherAppsAndStopRemovesTheTunnel() {
        assertTrue(resolvesNormally(BLOCKED))
        val startedAt = System.currentTimeMillis()

        AdBlockVpnService.start(context)
        waitUntil("the VPN to run", 30_000) { AdBlockVpnService.state.value == VpnState.RUNNING }
        waitUntil("the VPN transport to appear", 10_000) { vpnTransportUp() }
        awaitHomeText(R.string.home_protected_desc)

        waitUntil("$BLOCKED to be sinkholed", 30_000) { sinkholed(BLOCKED) }
        assertTrue(resolvesNormally(CLEAN))
        waitUntil("the blocked lookup to be logged", 15_000) { loggedAsBlocked(BLOCKED, startedAt) != null }
        assertEquals(SINKHOLE, loggedAsBlocked(BLOCKED, startedAt)!!.resolvedIp.ifEmpty { SINKHOLE })

        AdBlockVpnService.stop(context)
        waitUntil("the VPN to stop", 15_000) { AdBlockVpnService.state.value == VpnState.STOPPED }
        waitUntil("the VPN transport to go away", 6_000) { !vpnTransportUp() }
        waitUntil("$BLOCKED to resolve normally again", 10_000) { resolvesNormally(BLOCKED) }
        awaitHomeText(R.string.home_unprotected_desc)
    }

    @Test
    fun whitelistedAppBypassesFiltering() {
        runBlocking { prefs.setWhitelistedApps(setOf(SHELL_PACKAGE)) }

        AdBlockVpnService.start(context)
        waitUntil("the VPN to run", 30_000) { AdBlockVpnService.state.value == VpnState.RUNNING }
        waitUntil("the VPN transport to appear", 10_000) { vpnTransportUp() }
        awaitHomeText(R.string.home_protected_desc)

        // Unwhitelisted, the lookup is sinkholed within a couple of seconds of this point (see the test above).
        repeat(5) {
            assertTrue(resolvesNormally(BLOCKED))
            Thread.sleep(1_000)
        }
    }

    @Test
    fun customRuleAddedFromTheAppWhileRunningBlocksAfterItsRestart() {
        runBlocking { customRules.getAll().filter { it.domain == BLOCKED }.forEach { customRules.delete(it) } }
        AdBlockVpnService.start(context)
        waitUntil("the VPN to run", 30_000) { AdBlockVpnService.state.value == VpnState.RUNNING }
        waitUntil("$BLOCKED to resolve through the tunnel", 30_000) { vpnTransportUp() && resolvesNormally(BLOCKED) }

        getKoin().get<CustomRulesViewModel>().addRule("||$BLOCKED^")

        waitUntil("$BLOCKED to be sinkholed", 30_000) { sinkholed(BLOCKED) }
        waitUntil("the VPN to be running again", 15_000) { AdBlockVpnService.state.value == VpnState.RUNNING }
    }

    private companion object {
        const val BLOCKED = "example.org"
        const val CLEAN = "example.com"
        const val SINKHOLE = "0.0.0.0"
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
