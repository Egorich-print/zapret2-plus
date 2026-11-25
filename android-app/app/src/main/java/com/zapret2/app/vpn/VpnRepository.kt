package com.zapret2.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.zapret2.app.AppDebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the VPN screen needs: the subscription, the servers it contains, the selected one,
 * the routing mode and the measured latency of every server.
 *
 * State lives here rather than in a service so the screen, the tunnel service and the network
 * guard all observe the same source of truth.
 */
@Singleton
class VpnRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val preferences = context.getSharedPreferences("zapret2_vpn", Context.MODE_PRIVATE)

    private val _nodes = MutableStateFlow<List<VpnNode>>(emptyList())
    val nodes: StateFlow<List<VpnNode>> = _nodes

    private val _status = MutableStateFlow(VpnStatus.STOPPED)
    val status: StateFlow<VpnStatus> = _status

    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId

    private val _subscriptionUrl = MutableStateFlow(
        preferences.getString(KEY_SUBSCRIPTION_URL, "").orEmpty(),
    )
    val subscriptionUrl: StateFlow<String> = _subscriptionUrl

    private val _mode = MutableStateFlow(
        NetworkMode.fromStorage(preferences.getString(KEY_MODE, null)),
    )
    val mode: StateFlow<NetworkMode> = _mode

    private val _latencies = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val latencies: StateFlow<Map<String, Int?>> = _latencies

    private val _autoSwitchEnabled =
        MutableStateFlow(preferences.getBoolean(KEY_AUTO_SWITCH, true))
    val autoSwitchEnabled: StateFlow<Boolean> = _autoSwitchEnabled

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _networkSnapshot = MutableStateFlow(NetworkSnapshot())
    val networkSnapshot: StateFlow<NetworkSnapshot> = _networkSnapshot

    private val _networkReason = MutableStateFlow("")
    val networkReason: StateFlow<String> = _networkReason

    val selectedNode: VpnNode?
        get() = _nodes.value.firstOrNull { it.id == _selectedNodeId.value }

    init {
        restoreNodes()
    }

    /** Restores the saved servers if the process was restarted before the screen opened. */
    fun ensureLoaded() {
        if (_nodes.value.isEmpty()) restoreNodes()
    }

    fun setSubscriptionUrl(url: String) {
        val trimmed = url.trim()
        _subscriptionUrl.value = trimmed
        preferences.edit().putString(KEY_SUBSCRIPTION_URL, trimmed).apply()
    }

    fun setMode(mode: NetworkMode) {
        _mode.value = mode
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun setAutoSwitchEnabled(enabled: Boolean) {
        _autoSwitchEnabled.value = enabled
        preferences.edit().putBoolean(KEY_AUTO_SWITCH, enabled).apply()
    }

    fun setStatus(status: VpnStatus) {
        _status.value = status
    }

    fun selectNode(node: VpnNode) {
        _selectedNodeId.value = node.id
        preferences.edit().putString(KEY_SELECTED_NODE, node.id).apply()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun reportMessage(text: String) {
        _message.value = text
    }

    /** The guard service publishes what the network is doing and why. */
    fun reportNetworkState(snapshot: NetworkSnapshot, reason: String) {
        _networkSnapshot.value = snapshot
        _networkReason.value = reason
    }

    /**
     * Downloads the subscription and replaces the server list with what it contains.
     *
     * A subscription is either a plain list of links or the same list in base64; both are
     * accepted, and servers that cannot be parsed are skipped rather than failing the whole
     * update.
     */
    suspend fun refreshSubscription(): Result<Int> = withContext(Dispatchers.IO) {
        val url = _subscriptionUrl.value
        if (url.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No subscription URL"))
        }
        runCatching {
            val body = download(url)
            val parsed = ShareLinkParser.parseAll(body)
            if (parsed.isEmpty()) {
                error("The subscription did not contain any supported server")
            }
            storeNodes(parsed)
            _nodes.value = parsed
            val selection = _selectedNodeId.value
            if (selection == null || parsed.none { it.id == selection }) {
                selectNode(parsed.first())
            }
            parsed.size
        }
    }

    /**
     * Measures how long every server takes to answer.
     *
     * The measurement is a TCP handshake against the server address, which is what a client can
     * check before the tunnel is up and it is enough to tell a dead server from a working one.
     */
    suspend fun testServers(
        servers: List<VpnNode> = _nodes.value,
        timeoutMillis: Int = 5_000,
    ): Map<String, Int?> = withContext(Dispatchers.IO) {
        val results = linkedMapOf<String, Int?>()
        servers.forEach { node ->
            results[node.id] = measure(node, timeoutMillis)
        }
        _latencies.value = results
        results
    }

    private fun measure(node: VpnNode, timeoutMillis: Int): Int? = runCatching {
        val socket = Socket()
        try {
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(node.server, node.port), timeoutMillis)
            ((System.nanoTime() - started) / 1_000_000L).toInt()
        } finally {
            runCatching { socket.close() }
        }
    }.getOrNull()

    private fun download(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("Subscription request failed with HTTP $code")
            }
            return connection.inputStream.use { readBody(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(stream: InputStream): String {
        val buffer = StringBuilder()
        BufferedInputStream(stream).use { input ->
            val chunk = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffer.append(String(chunk, 0, read, Charsets.UTF_8))
                if (buffer.length > MAX_BODY_CHARS) error("The subscription is too large")
            }
        }
        return buffer.toString()
    }

    private fun storeNodes(nodes: List<VpnNode>) {
        val payload = MiniJson.write(
            nodes.map { node ->
                linkedMapOf<String, Any?>(
                    "id" to node.id,
                    "name" to node.name,
                    "link" to node.link,
                )
            },
        )
        preferences.edit().putString(KEY_NODES, payload).apply()
    }

    private fun restoreNodes() {
        val payload = preferences.getString(KEY_NODES, null) ?: return
        val parsed = MiniJson.parse(payload) as? List<*> ?: return
        val restored = parsed.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val link = map["link"] as? String ?: return@mapNotNull null
            ShareLinkParser.parse(link)
        }
        _nodes.value = restored
        val selection = preferences.getString(KEY_SELECTED_NODE, null)
        _selectedNodeId.value = selection?.takeIf { id -> restored.any { it.id == id } }
            ?: restored.firstOrNull()?.id
    }

    /**
     * The network the tunnel should ride on, when the platform can tell us.
     */
    fun underlyingNetworkFor(mode: NetworkMode): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return null
        val wanted = when (mode) {
            NetworkMode.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            NetworkMode.LTE -> NetworkCapabilities.TRANSPORT_CELLULAR
            NetworkMode.AUTO -> return null
        }
        if (!capabilities.hasTransport(wanted)) return null
        return manager.activeNetwork
    }

    private companion object {
        const val KEY_SUBSCRIPTION_URL = "subscription_url"
        const val KEY_SELECTED_NODE = "selected_node"
        const val KEY_NODES = "nodes"
        const val KEY_MODE = "network_mode"
        const val KEY_AUTO_SWITCH = "auto_switch"
        const val USER_AGENT = "Zapret2Plus/2.3.12"
        const val MAX_BODY_CHARS = 2 * 1024 * 1024
    }
}

/** Small helper so the repository can report failures without throwing into the UI. */
internal fun Throwable.readableMessage(): String {
    AppDebugLog.error("Zapret2Vpn", "VPN failure", this)
    return message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
