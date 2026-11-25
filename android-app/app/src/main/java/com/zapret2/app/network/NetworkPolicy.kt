package com.zapret2.app.network

import com.zapret2.app.vpn.NetworkMode
import com.zapret2.app.vpn.NetworkSnapshot
import com.zapret2.app.vpn.NetworkTransport

/**
 * The rule that decides which radio the device should be on.
 *
 * The policy is a pure function so it can be checked without a device: the guard service only
 * carries out what it decides.
 *
 * In automatic mode the device stays on Wi-Fi while it works, falls back to mobile data when
 * Wi-Fi stops delivering traffic, and probes Wi-Fi again until it recovers.
 */
internal object NetworkPolicy {

    /** How long Wi-Fi may be connected without working before the device moves to mobile data. */
    const val FAILOVER_AFTER_MILLIS = 15_000L

    /** How long a Wi-Fi probe may take before it is called a failure. */
    const val PROBE_TIMEOUT_MILLIS = 25_000L

    /** How long the device waits before it tries Wi-Fi again. */
    const val PROBE_INTERVAL_MILLIS = 60_000L

    data class Decision(
        val wifi: Boolean? = null,
        val mobileData: Boolean? = null,
        val reconnectTunnel: Boolean = false,
        val reason: String = "",
    )

    data class State(
        val unhealthySince: Long? = null,
        val probeStartedAt: Long? = null,
        val lastProbeAt: Long = 0L,
    )

    fun decide(
        mode: NetworkMode,
        snapshot: NetworkSnapshot,
        now: Long,
        state: State,
    ): Pair<Decision, State> = when (mode) {
        NetworkMode.WIFI -> Decision(
            wifi = true,
            mobileData = false,
            reason = "Wi-Fi only",
        ) to state.copy(unhealthySince = null, probeStartedAt = null)

        NetworkMode.LTE -> Decision(
            wifi = false,
            mobileData = true,
            reason = "Mobile data only",
        ) to state.copy(unhealthySince = null, probeStartedAt = null)

        NetworkMode.AUTO -> decideAutomatic(snapshot, now, state)
    }

    private fun decideAutomatic(
        snapshot: NetworkSnapshot,
        now: Long,
        state: State,
    ): Pair<Decision, State> = when (snapshot.transport) {
        NetworkTransport.WIFI -> {
            if (snapshot.validated) {
                Decision(wifi = true, reason = "Wi-Fi works") to
                    state.copy(unhealthySince = null, probeStartedAt = null)
            } else {
                val unhealthySince = state.unhealthySince ?: now
                if (now - unhealthySince >= FAILOVER_AFTER_MILLIS) {
                    Decision(
                        wifi = false,
                        reconnectTunnel = true,
                        reason = "Wi-Fi stopped working, moving to mobile data",
                    ) to state.copy(unhealthySince = now, probeStartedAt = null)
                } else {
                    Decision(reason = "Waiting for Wi-Fi") to
                        state.copy(unhealthySince = unhealthySince)
                }
            }
        }

        NetworkTransport.CELLULAR, NetworkTransport.ETHERNET, NetworkTransport.OTHER -> {
            val probeStartedAt = state.probeStartedAt
            if (probeStartedAt != null) {
                if (now - probeStartedAt >= PROBE_TIMEOUT_MILLIS) {
                    Decision(wifi = false, reason = "Wi-Fi did not come back") to
                        state.copy(probeStartedAt = null)
                } else {
                    Decision(reason = "Waiting for Wi-Fi to come back") to state
                }
            } else if (now - state.lastProbeAt >= PROBE_INTERVAL_MILLIS) {
                Decision(wifi = true, reason = "Checking whether Wi-Fi is back") to
                    state.copy(probeStartedAt = now, lastProbeAt = now)
            } else {
                Decision(reason = "Staying on mobile data") to state
            }
        }

        NetworkTransport.NONE -> Decision(reason = "No network") to state
    }
}
