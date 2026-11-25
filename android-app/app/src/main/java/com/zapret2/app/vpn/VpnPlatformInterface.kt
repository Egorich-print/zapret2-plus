package com.zapret2.app.vpn

import android.net.Network
import android.net.VpnService
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState

/**
 * The Android side of the sing-box core.
 *
 * The core calls back into this class for everything it cannot do by itself: opening the TUN
 * interface, protecting the tunnel's own sockets and reporting the current Wi-Fi state. The
 * features this client does not use (bridges, tailscale, an SSH agent) answer with a clear
 * "not supported" instead of pretending to work.
 */
internal class VpnPlatformInterface(
    private val service: VpnService,
    private val underlyingNetwork: Network? = null,
    private val wifiStateProvider: () -> WIFIState = { WIFIState("", "") },
) : PlatformInterface {

    override fun openTun(options: TunOptions): Int {
        val builder = service.Builder().apply {
            setSession(SESSION_NAME)
            setMtu(options.getMTU())
            addAddress(TUN_ADDRESS_V4, 30)
            addAddress(TUN_ADDRESS_V6, 126)
            if (options.getDNSMode().value == Libbox.DNSModeHijack) {
                addAddress(TUN_DNS_ADDRESS_V4, 32)
                addAddress(TUN_DNS_ADDRESS_V6, 128)
                addDnsServer(TUN_DNS_ADDRESS_V4)
                addDnsServer(TUN_DNS_ADDRESS_V6)
            }
            if (underlyingNetwork != null && android.os.Build.VERSION.SDK_INT >= 33) {
                setUnderlyingNetworks(arrayOf(underlyingNetwork))
            }
        }
        val tun = builder.establish() ?: error("The system refused to open the tunnel")
        return tun.detachFd()
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        service.protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun includeAllNetworks(): Boolean = true

    override fun underNetworkExtension(): Boolean = false

    override fun usePlatformBridge(): Boolean = false

    override fun usePlatformShell(): Boolean = false

    override fun readWIFIState(): WIFIState = wifiStateProvider()

    override fun registerMyInterface(name: String) = Unit

    override fun clearDNSCache() = Unit

    override fun checkPlatformShell() = Unit

    override fun cancelNotification(id: String, notificationId: Int) = Unit

    override fun sendNotification(notification: Notification) = Unit

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator = EmptyNetworkInterfaceIterator

    override fun tailscaleHostname(): String = ""

    override fun readSystemSSHHostKey(): String = ""

    override fun lookupSFTPServer(): String = ""

    override fun lookupUser(username: String): PlatformUser = PlatformUser().apply {
        setUsername(username)
    }

    override fun createBridge(options: BridgeOptions): BridgeSession =
        throw UnsupportedOperationException("Bridges are not supported by this client")

    override fun findConnectionOwner(
        uid: Int,
        processName: String,
        pid: Int,
        packageName: String,
        pidNamespace: Int,
    ): ConnectionOwner = throw UnsupportedOperationException("Connection owners are not supported")

    override fun localDNSTransport(): LocalDNSTransport =
        throw UnsupportedOperationException("The local DNS transport is not supported")

    override fun openShellSession(
        user: PlatformUser,
        shell: String,
        env: StringIterator,
        dir: String,
        pty: Int,
        uid: Int,
    ): ShellSession = throw UnsupportedOperationException("Shell sessions are not supported")

    private object EmptyNetworkInterfaceIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false

        override fun next(): io.nekohasekai.libbox.NetworkInterface =
            throw NoSuchElementException("No network interfaces are reported")
    }

    private companion object {
        const val SESSION_NAME = "Zapret2 Plus"
        const val TUN_ADDRESS_V4 = "172.19.0.1"
        const val TUN_ADDRESS_V6 = "fdfe:dcba:9876::1"
        const val TUN_DNS_ADDRESS_V4 = "172.19.0.2"
        const val TUN_DNS_ADDRESS_V6 = "fdfe:dcba:9876::2"
    }
}
