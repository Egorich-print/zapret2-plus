package com.zapret2.app.vpn

/** Protocol families this client understands, mapped to what a subscription link may carry. */
enum class VpnProtocol(val displayName: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("Shadowsocks"),
    HYSTERIA2("Hysteria2"),
    TUIC("TUIC"),
    UNKNOWN("Unknown"),
}

/**
 * One server from a subscription.
 *
 * [outbound] is the sing-box outbound object for the server, minus the tag: the tag is assigned
 * when the configuration is generated, so the same node can be reused in any group.
 */
data class VpnNode(
    val id: String,
    val name: String,
    val protocol: VpnProtocol,
    val server: String,
    val port: Int,
    val link: String,
    val outbound: Map<String, Any?>,
)

/** What the tunnel is doing right now. */
enum class VpnStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

/** Which radio the tunnel is allowed to use. */
enum class NetworkMode {
    /** Wi-Fi while it works, mobile data when it does not, and back again. */
    AUTO,
    /** Wi-Fi only: mobile data stays off. */
    WIFI,
    /** Mobile data only: Wi-Fi stays off. */
    LTE,
    ;

    companion object {
        fun fromStorage(value: String?): NetworkMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/** The network the device is using right now. */
enum class NetworkTransport {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
}

data class NetworkSnapshot(
    val transport: NetworkTransport = NetworkTransport.NONE,
    val validated: Boolean = false,
    val available: Boolean = false,
)
