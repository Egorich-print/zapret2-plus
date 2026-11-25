package com.zapret2.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkParserTest {

    @Test
    fun `parses a vless reality link`() {
        val link = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?security=reality&sni=example.com&fp=chrome&pbk=key&sid=ab&type=ws&path=%2Fws" +
            "#Berlin"

        val node = ShareLinkParser.parse(link)

        assertNotNull(node)
        assertEquals(VpnProtocol.VLESS, node?.protocol)
        assertEquals("example.com", node?.server)
        assertEquals(443, node?.port)
        assertEquals("Berlin", node?.name)

        val outbound = node?.outbound ?: error("outbound missing")
        assertEquals("vless", outbound["type"])
        assertEquals("11111111-1111-1111-1111-111111111111", outbound["uuid"])

        val tls = outbound["tls"] as? Map<*, *> ?: error("tls missing")
        assertEquals(true, tls["enabled"])
        assertEquals("example.com", tls["server_name"])
        val reality = tls["reality"] as? Map<*, *> ?: error("reality missing")
        assertEquals("key", reality["public_key"])
        assertEquals("ab", reality["short_id"])

        val transport = outbound["transport"] as? Map<*, *> ?: error("transport missing")
        assertEquals("ws", transport["type"])
        assertEquals("/ws", transport["path"])
    }

    @Test
    fun `parses a vmess link`() {
        val payload = """
            {"v":"2","ps":"Amsterdam","add":"vm.example.com","port":"443","id":"22222222-2222-2222-2222-222222222222","aid":0,"net":"ws","type":"none","host":"vm.example.com","path":"/vm","tls":"tls"}
        """.trimIndent()
        val link = "vmess://" + Base64Codec.encode(payload)

        val node = ShareLinkParser.parse(link)

        assertEquals(VpnProtocol.VMESS, node?.protocol)
        assertEquals("vm.example.com", node?.server)
        assertEquals(443, node?.port)
        assertEquals("Amsterdam", node?.name)
        val outbound = node?.outbound ?: error("outbound missing")
        assertEquals("vmess", outbound["type"])
        assertEquals(0, outbound["alter_id"])
        assertEquals("vm.example.com", (outbound["tls"] as Map<*, *>)["server_name"])
        assertEquals("/vm", (outbound["transport"] as Map<*, *>)["path"])
    }

    @Test
    fun `parses a trojan link`() {
        val node = ShareLinkParser.parse("trojan://secret@tr.example.com:8443?sni=tr.example.com&allowInsecure=1#Troy")

        assertEquals(VpnProtocol.TROJAN, node?.protocol)
        assertEquals("secret", node?.outbound?.get("password"))
        assertEquals(8443, node?.port)
        assertEquals(true, (node?.outbound?.get("tls") as? Map<*, *>)?.get("insecure"))
    }

    @Test
    fun `parses a shadowsocks link`() {
        val link = "ss://" + Base64Codec.encode("aes-256-gcm:pass") + "@ss.example.com:8388#SSR"

        val node = ShareLinkParser.parse(link)

        assertEquals(VpnProtocol.SHADOWSOCKS, node?.protocol)
        assertEquals("aes-256-gcm", node?.outbound?.get("method"))
        assertEquals("pass", node?.outbound?.get("password"))
        assertEquals(8388, node?.port)
    }

    @Test
    fun `reads a base64 subscription body`() {
        val body = "vless://11111111-1111-1111-1111-111111111111@a.example.com:443#A\n" +
            "trojan://secret@b.example.com:443#B"

        val nodes = ShareLinkParser.parseAll(Base64Codec.encode(body))

        assertEquals(2, nodes.size)
        assertEquals(listOf("A", "B"), nodes.map { it.name })
        assertTrue(nodes.map { it.id }.distinct().size == 2)
    }

    @Test
    fun `ignores links it cannot understand`() {
        assertNull(ShareLinkParser.parse("https://example.com/subscription"))
        assertTrue(ShareLinkParser.parseAll("nothing useful here").isEmpty())
    }
}
