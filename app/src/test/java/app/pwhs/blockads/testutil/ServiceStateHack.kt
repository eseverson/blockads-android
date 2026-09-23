package app.pwhs.blockads.testutil

import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.service.VpnState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The services keep their state in private companion flows. Until a VpnStateRepository seam exists,
 * tests poke them via reflection and must reset to STOPPED afterwards.
 */
object ServiceStateHack {
    @Suppress("UNCHECKED_CAST")
    private fun flow(owner: Class<*>): MutableStateFlow<VpnState> =
        owner.getDeclaredField("_state").apply { isAccessible = true }.get(null) as MutableStateFlow<VpnState>

    fun setVpn(state: VpnState) {
        flow(AdBlockVpnService::class.java).value = state
    }

    fun setRoot(state: VpnState) {
        flow(RootProxyService::class.java).value = state
    }

    fun reset() {
        setVpn(VpnState.STOPPED)
        setRoot(VpnState.STOPPED)
    }
}
