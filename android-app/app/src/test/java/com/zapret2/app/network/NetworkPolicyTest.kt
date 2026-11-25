package com.zapret2.app.network

import com.zapret2.app.vpn.NetworkMode
import com.zapret2.app.vpn.NetworkSnapshot
import com.zapret2.app.vpn.NetworkTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    private val wifiWorking = NetworkSnapshot(
        transport = NetworkTransport.WIFI,
        validated = true,
        available = true,
    )

    private val wifiBroken = NetworkSnapshot(
        transport = NetworkTransport.WIFI,
        validated = false,
        available = false,
    )

    private val onMobile = NetworkSnapshot(
        transport = NetworkTransport.CELLULAR,
        validated = true,
        available = true,
    )

    @Test
    fun `wifi mode pins the radios`() {
        val (decision, _) = NetworkPolicy.decide(
            mode = NetworkMode.WIFI,
            snapshot = onMobile,
            now = 0L,
            state = NetworkPolicy.State(),
        )

        assertEquals(true, decision.wifi)
        assertEquals(false, decision.mobileData)
    }

    @Test
    fun `mobile mode pins the radios`() {
        val (decision, _) = NetworkPolicy.decide(
            mode = NetworkMode.LTE,
            snapshot = wifiWorking,
            now = 0L,
            state = NetworkPolicy.State(),
        )

        assertEquals(false, decision.wifi)
        assertEquals(true, decision.mobileData)
    }

    @Test
    fun `automatic mode stays on working wifi`() {
        val (decision, state) = NetworkPolicy.decide(
            mode = NetworkMode.AUTO,
            snapshot = wifiWorking,
            now = 1_000L,
            state = NetworkPolicy.State(),
        )

        assertEquals(true, decision.wifi)
        assertNull(state.unhealthySince)
        assertTrue(!decision.reconnectTunnel)
    }

    @Test
    fun `automatic mode moves to mobile data when wifi stops working`() {
        val (decision, _) = NetworkPolicy.decide(
            mode = NetworkMode.AUTO,
            snapshot = wifiBroken,
            now = 60_000L,
            state = NetworkPolicy.State(unhealthySince = 0L),
        )

        assertEquals(false, decision.wifi)
        assertTrue(decision.reconnectTunnel)
    }

    @Test
    fun `automatic mode waits before it moves`() {
        val (decision, state) = NetworkPolicy.decide(
            mode = NetworkMode.AUTO,
            snapshot = wifiBroken,
            now = 1_000L,
            state = NetworkPolicy.State(),
        )

        assertNull(decision.wifi)
        assertEquals(1_000L, state.unhealthySince)
    }

    @Test
    fun `automatic mode probes wifi again from mobile data`() {
        val (decision, state) = NetworkPolicy.decide(
            mode = NetworkMode.AUTO,
            snapshot = onMobile,
            now = 120_000L,
            state = NetworkPolicy.State(lastProbeAt = 0L),
        )

        assertEquals(true, decision.wifi)
        assertEquals(120_000L, state.probeStartedAt)
    }

    @Test
    fun `automatic mode gives up on a probe that did not work`() {
        val (decision, state) = NetworkPolicy.decide(
            mode = NetworkMode.AUTO,
            snapshot = onMobile,
            now = 120_000L,
            state = NetworkPolicy.State(probeStartedAt = 0L, lastProbeAt = 0L),
        )

        assertEquals(false, decision.wifi)
        assertNull(state.probeStartedAt)
    }
}
