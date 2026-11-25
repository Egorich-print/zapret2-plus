package com.zapret2.app.vpn

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Turns subscription share links into sing-box outbounds.
 *
 * Subscription providers hand out links in a handful of shapes: `vless://`, `vmess://`,
 * `trojan://`, `ss://`, `hysteria2://` and `tuic://`. Each carries its transport and TLS
 * settings in the query string, so one parser covers them all instead of one parser per protocol.
 */
internal object ShareLinkParser {

    fun parseAll(text: String): List<VpnNode> =
        extractLinks(text).mapNotNull { parse(it) }.distinctBy { it.id }

    /** A subscription body is either a plain list of links or the same list in base64. */
    fun extractLinks(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val decoded = decodeBase64IfItLooksEncoded(trimmed)
        val source = decoded?.takeIf { it.contains("://") } ?: trimmed
        return source.lineSequence()
            .map { it.trim() }
            .filter { it.contains("://") }
            .map { it.substringBefore(' ') }
            .toList()
    }

    fun parse(link: String): VpnNode? {
        val trimmed = link.trim()
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty()) return null
        return when (scheme.lowercase()) {
            "vless" -> parseVless(trimmed)
            "vmess" -> parseVmess(trimmed)
            "trojan" -> parseTrojan(trimmed)
            "ss" -> parseShadowsocks(trimmed)
            "hysteria2", "hy2" -> parseHysteria2(trimmed)
            "tuic" -> parseTuic(trimmed)
            else -> null
        }
    }

    private fun parseVless(link: String): VpnNode? {
        val parts = splitLink(link) ?: return null
        val uuid = parts.userInfo.substringBefore(':').trim()
        if (uuid.isEmpty()) return null
        val outbound = linkedMapOf<String, Any?>(
            "type" to "vless",
            "server" to parts.host,
            "server_port" to parts.port,
            "uuid" to uuid,
        )
        parts.query["flow"]?.takeIf { it.isNotEmpty() }?.let { outbound["flow"] = it }
        buildTls(parts.query, defaultEnabled = false)?.let { outbound["tls"] = it }
        buildTransport(parts.query)?.let { outbound["transport"] = it }
        return node(VpnProtocol.VLESS, parts, outbound)
    }

    private fun parseVmess(link: String): VpnNode? {
        val payload = link.substringAfter("://").substringBefore('#')
        val decoded = decodeBase64IfItLooksEncoded(payload.substringBefore('?')) ?: return null
        val parsed = MiniJson.parse(decoded) as? Map<*, *> ?: return null
        val server = parsed.string("add").ifEmpty { return null }
        val port = parsed.int("port")
        val uuid = parsed.string("id")
        if (uuid.isEmpty()) return null
        val outbound = linkedMapOf<String, Any?>(
            "type" to "vmess",
            "server" to server,
            "server_port" to port,
            "uuid" to uuid,
            "alter_id" to (parsed.int("aid")),
            "security" to parsed.string("scy").ifEmpty { "auto" },
        )
        val network = parsed.string("net").lowercase()
        val host = parsed.string("host")
        val path = parsed.string("path")
        val query = linkedMapOf<String, String>()
        when (network) {
            "ws", "websocket" -> {
                query["type"] = "ws"
                path.takeIf { it.isNotEmpty() }?.let { query["path"] = it }
                host.takeIf { it.isNotEmpty() }?.let { query["host"] = it }
            }
            "grpc" -> {
                query["type"] = "grpc"
                path.takeIf { it.isNotEmpty() }?.let { query["serviceName"] = it }
            }
            "http", "h2" -> {
                query["type"] = "http"
                path.takeIf { it.isNotEmpty() }?.let { query["path"] = it }
                host.takeIf { it.isNotEmpty() }?.let { query["host"] = it }
            }
            else -> Unit
        }
        val tlsMode = parsed.string("tls").lowercase()
        if (tlsMode == "tls" || tlsMode == "reality") {
            val tlsQuery = linkedMapOf<String, String>()
            parsed.string("sni").takeIf { it.isNotEmpty() }?.let { tlsQuery["sni"] = it }
            parsed.string("host").takeIf { it.isNotEmpty() }?.let { tlsQuery["sni"] = it }
            buildTls(tlsQuery, defaultEnabled = true)?.let { outbound["tls"] = it }
        }
        buildTransport(query)?.let { outbound["transport"] = it }
        val name = parsed.string("ps")
        return node(
            protocol = VpnProtocol.VMESS,
            parts = LinkParts(
                userInfo = uuid,
                host = server,
                port = port,
                query = query,
                fragment = name,
                raw = link,
            ),
            outbound = outbound,
        )
    }

    private fun parseTrojan(link: String): VpnNode? {
        val parts = splitLink(link) ?: return null
        val password = parts.userInfo.substringBefore(':')
        if (password.isEmpty()) return null
        val outbound = linkedMapOf<String, Any?>(
            "type" to "trojan",
            "server" to parts.host,
            "server_port" to parts.port,
            "password" to password,
        )
        buildTls(parts.query, defaultEnabled = true)?.let { outbound["tls"] = it }
        buildTransport(parts.query)?.let { outbound["transport"] = it }
        return node(VpnProtocol.TROJAN, parts, outbound)
    }

    private fun parseShadowsocks(link: String): VpnNode? {
        val body = link.substringAfter("://").substringBefore('#')
        val credentials = body.substringBefore('?')
        val query = parseQuery(body.substringAfter('?', ""))
        var method: String
        var password: String
        var host: String
        var port: Int
        if (credentials.contains('@')) {
            val userInfo = credentials.substringBeforeLast('@')
            val hostPort = credentials.substringAfterLast('@')
            val decodedUser = decodeBase64IfItLooksEncoded(userInfo) ?: userInfo
            method = decodedUser.substringBefore(':')
            password = decodedUser.substringAfter(':', "")
            host = hostOf(hostPort)
            port = portOf(hostPort)
        } else {
            val decoded = decodeBase64IfItLooksEncoded(credentials) ?: return null
            method = decoded.substringBefore(':')
            password = decoded.substringAfter(':', "")
            host = hostOf(decoded.substringAfterLast('@'))
            port = portOf(decoded.substringAfterLast('@'))
        }
        if (method.isEmpty() || host.isEmpty()) return null
        val outbound = linkedMapOf<String, Any?>(
            "type" to "shadowsocks",
            "server" to host,
            "server_port" to port,
            "method" to method,
            "password" to password,
        )
        return node(
            protocol = VpnProtocol.SHADOWSOCKS,
            parts = LinkParts(
                userInfo = password,
                host = host,
                port = port,
                query = query,
                fragment = "",
                raw = link,
            ),
            outbound = outbound,
        )
    }

    private fun parseHysteria2(link: String): VpnNode? {
        val parts = splitLink(link) ?: return null
        val password = parts.userInfo.substringBefore(':')
        if (password.isEmpty()) return null
        val outbound = linkedMapOf<String, Any?>(
            "type" to "hysteria2",
            "server" to parts.host,
            "server_port" to parts.port,
            "password" to password,
        )
        buildTls(parts.query, defaultEnabled = true)?.let { outbound["tls"] = it }
        val obfsPassword = parts.query["obfs-password"].orEmpty()
        if (obfsPassword.isNotEmpty()) {
            outbound["obfs"] = linkedMapOf<String, Any?>(
                "type" to (parts.query["obfs"] ?: "salamander"),
                "password" to obfsPassword,
            )
        }
        parts.query["up"]?.toIntOrNull()?.let { outbound["up_mbps"] = it }
        parts.query["down"]?.toIntOrNull()?.let { outbound["down_mbps"] = it }
        return node(VpnProtocol.HYSTERIA2, parts, outbound)
    }

    private fun parseTuic(link: String): VpnNode? {
        val parts = splitLink(link) ?: return null
        val uuid = parts.userInfo.substringBefore(':')
        val password = parts.userInfo.substringAfter(':', "")
        if (uuid.isEmpty()) return null
        val outbound = linkedMapOf<String, Any?>(
            "type" to "tuic",
            "server" to parts.host,
            "server_port" to parts.port,
            "uuid" to uuid,
            "password" to password,
            "congestion_control" to (parts.query["congestion_control"] ?: "bbr"),
            "udp_relay_mode" to (parts.query["udp_relay_mode"] ?: "native"),
        )
        buildTls(parts.query, defaultEnabled = true)?.let { outbound["tls"] = it }
        return node(VpnProtocol.TUIC, parts, outbound)
    }

    private fun buildTls(query: Map<String, String>, defaultEnabled: Boolean): Map<String, Any?>? {
        val security = query["security"]?.lowercase().orEmpty()
        val reality = security == "reality"
        val enabled = when (security) {
            "none" -> false
            "reality", "tls" -> true
            else -> defaultEnabled || query.containsKey("sni") || query.containsKey("insecure")
        }
        if (!enabled && !reality) return null
        val tls = linkedMapOf<String, Any?>()
        tls["enabled"] = true
        query["sni"]?.takeIf { it.isNotEmpty() }?.let { tls["server_name"] = it }
        query["peer"]?.takeIf { it.isNotEmpty() }?.let { tls["server_name"] = it }
        query["host"]?.takeIf { it.isNotEmpty() }?.let { tls["server_name"] = it }
        if (query["allowInsecure"] == "1" || query["insecure"] == "1") tls["insecure"] = true
        query["alpn"]?.takeIf { it.isNotEmpty() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { tls["alpn"] = it }
        query["fp"]?.takeIf { it.isNotEmpty() }?.let { fingerprint ->
            val utls = linkedMapOf<String, Any?>()
            utls["enabled"] = true
            utls["fingerprint"] = fingerprint
            tls["utls"] = utls
        }
        if (reality) {
            val realityOptions = linkedMapOf<String, Any?>()
            realityOptions["enabled"] = true
            query["pbk"]?.takeIf { it.isNotEmpty() }?.let { realityOptions["public_key"] = it }
            query["sid"]?.takeIf { it.isNotEmpty() }?.let { realityOptions["short_id"] = it }
            tls["reality"] = realityOptions
        }
        return tls
    }

    private fun buildTransport(query: Map<String, String>): Map<String, Any?>? {
        val type = query["type"]?.lowercase().orEmpty()
        val host = query["host"].orEmpty()
        return when (type) {
            "ws", "websocket" -> {
                val transport = linkedMapOf<String, Any?>()
                transport["type"] = "ws"
                transport["path"] = query["path"]?.takeIf { it.isNotEmpty() } ?: "/"
                if (host.isNotEmpty()) {
                    val headers = linkedMapOf<String, Any?>()
                    headers["Host"] = host
                    transport["headers"] = headers
                }
                transport
            }
            "grpc" -> {
                val transport = linkedMapOf<String, Any?>()
                transport["type"] = "grpc"
                transport["service_name"] = query["serviceName"]?.takeIf { it.isNotEmpty() }
                    ?: query["path"]?.takeIf { it.isNotEmpty() }
                    ?: ""
                transport
            }
            "http", "h2" -> {
                val transport = linkedMapOf<String, Any?>()
                transport["type"] = "http"
                transport["path"] = query["path"]?.takeIf { it.isNotEmpty() } ?: "/"
                if (host.isNotEmpty()) {
                    transport["host"] = listOf(host)
                }
                transport
            }
            else -> null
        }
    }

    private fun node(
        protocol: VpnProtocol,
        parts: LinkParts,
        outbound: Map<String, Any?>,
    ): VpnNode {
        val name = parts.fragment.ifEmpty {
            "${parts.host}:${parts.port}"
        }
        return VpnNode(
            id = fingerprint(parts.raw),
            name = decodeFragment(parts.fragment).ifEmpty { name },
            protocol = protocol,
            server = parts.host,
            port = parts.port,
            link = parts.raw,
            outbound = outbound,
        )
    }

    private fun splitLink(link: String): LinkParts? {
        val schemeEnd = link.indexOf("://")
        if (schemeEnd <= 0) return null
        var rest = link.substring(schemeEnd + 3)
        var fragment = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            fragment = rest.substring(hash + 1)
            rest = rest.substring(0, hash)
        }
        var query = ""
        val question = rest.indexOf('?')
        if (question >= 0) {
            query = rest.substring(question + 1)
            rest = rest.substring(0, question)
        }
        rest = rest.trimEnd('/')
        val userInfo = rest.substringBeforeLast('@', missingDelimiterValue = "")
        val hostPort = if (userInfo.isEmpty()) rest else rest.substringAfterLast('@')
        val host = hostOf(hostPort)
        val port = portOf(hostPort)
        if (host.isEmpty() || port <= 0) return null
        return LinkParts(
            userInfo = userInfo,
            host = host,
            port = port,
            query = parseQuery(query),
            fragment = fragment,
            raw = link,
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&')
            .mapNotNull { pair ->
                val name = pair.substringBefore('=')
                if (name.isEmpty()) null else name to urlDecode(pair.substringAfter('=', ""))
            }
            .toMap()
    }

    private fun urlDecode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun decodeFragment(value: String): String = urlDecode(value)

    private fun hostOf(hostPort: String): String {
        if (hostPort.startsWith("[")) {
            return hostPort.substringAfter('[').substringBefore(']')
        }
        return hostPort.substringBefore(':')
    }

    private fun portOf(hostPort: String): Int {
        val tail = if (hostPort.startsWith("[")) {
            hostPort.substringAfter(']', "")
        } else {
            hostPort.substringAfter(':', "")
        }
        if (tail.isEmpty()) return 0
        return tail.toIntOrNull() ?: 0
    }

    private fun decodeBase64IfItLooksEncoded(value: String): String? {
        val compact = value.trim()
        if (compact.isEmpty()) return null
        if (compact.any { it.isWhitespace() }) return null
        val normalized = compact.replace('-', '+').replace('_', '/')
        if (normalized.any { !it.isLetterOrDigit() && it != '+' && it != '/' && it != '=' }) return null
        return Base64Codec.decode(normalized)?.takeIf { it.isNotEmpty() }
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun Map<*, *>.string(key: String): String = (this[key] as? String).orEmpty()

    private fun Map<*, *>.int(key: String): Int = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        else -> 0
    }

    private data class LinkParts(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
        val raw: String,
    )
}
