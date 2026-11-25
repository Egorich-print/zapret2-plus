package com.zapret2.app.vpn

/**
 * Builds the sing-box configuration the tunnel runs with.
 *
 * The configuration is deliberately small: one outbound for the selected server, a direct
 * outbound for the traffic that must not enter the tunnel, and a local resolver so the server
 * address can be resolved before the tunnel is up.
 *
 * The shape is validated against sing-box 1.14 with `sing-box check`.
 */
internal object VpnConfigFactory {

    const val TUN_INBOUND_TAG = "tun-in"
    const val DIRECT_OUTBOUND_TAG = "direct"
    const val DNS_TAG = "dns-local"

    fun build(
        node: VpnNode,
        nodes: List<VpnNode> = emptyList(),
        mode: NetworkMode = NetworkMode.AUTO,
    ): String {
        val outbounds = mutableListOf<Map<String, Any?>>()
        val tags = mutableListOf<String>()
        nodes.forEach { candidate ->
            if (candidate.id == node.id) {
                val tag = outboundTag(candidate)
                outbounds += candidate.outbound + mapOf("tag" to tag)
                tags += tag
            }
        }
        if (tags.isEmpty()) {
            outbounds += node.outbound + mapOf("tag" to outboundTag(node))
            tags += outboundTag(node)
        }
        outbounds += mapOf(
            "type" to "direct",
            "tag" to DIRECT_OUTBOUND_TAG,
        )

        val inbound = linkedMapOf<String, Any?>()
        inbound["type"] = "tun"
        inbound["tag"] = TUN_INBOUND_TAG
        inbound["address"] = listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")
        inbound["mtu"] = 9000
        inbound["auto_route"] = true
        inbound["strict_route"] = true
        inbound["dns_mode"] = "hijack"

        val dns = linkedMapOf<String, Any?>()
        val dnsServer = linkedMapOf<String, Any?>()
        dnsServer["tag"] = DNS_TAG
        dnsServer["type"] = "local"
        dns["servers"] = listOf(dnsServer)
        dns["final"] = DNS_TAG

        val privateBypass = linkedMapOf<String, Any?>()
        privateBypass["action"] = "route"
        privateBypass["outbound"] = DIRECT_OUTBOUND_TAG
        privateBypass["ip_is_private"] = true

        val resolver = linkedMapOf<String, Any?>()
        resolver["server"] = DNS_TAG

        val route = linkedMapOf<String, Any?>()
        route["auto_detect_interface"] = true
        route["default_domain_resolver"] = resolver
        route["rules"] = listOf(privateBypass)
        route["final"] = tags.first()

        val config = linkedMapOf<String, Any?>()
        config["log"] = linkedMapOf<String, Any?>().apply {
            this["level"] = "info"
            this["timestamp"] = true
        }
        config["dns"] = dns
        config["inbounds"] = listOf(inbound)
        config["outbounds"] = outbounds
        config["route"] = route
        return MiniJson.write(config)
    }

    fun outboundTag(node: VpnNode): String = "node-" + node.id

    /**
     * The transport the requested mode expects.
     *
     * Servers that only answer over mobile data are common in Russian providers, so the UI shows
     * which transport the current mode is asking for.
     */
    fun preferredTransport(mode: NetworkMode): NetworkTransport = when (mode) {
        NetworkMode.AUTO -> NetworkTransport.NONE
        NetworkMode.WIFI -> NetworkTransport.WIFI
        NetworkMode.LTE -> NetworkTransport.CELLULAR
    }
}
