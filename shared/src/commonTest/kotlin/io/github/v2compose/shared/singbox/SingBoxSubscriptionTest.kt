package io.github.v2compose.shared.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class SingBoxShareLinkTest {

    private fun String.base64(): String = Base64.encode(encodeToByteArray())

    @Test
    fun parsesVlessWithWebSocketAndTls() {
        val link = "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2Fws#My%20Node"
        val outbound = SingBoxShareLink.parse(link)
        assertNotNull(outbound)
        assertEquals("vless", outbound.string("type"))
        assertEquals("My Node", outbound.string("tag"))
        assertEquals("example.com", outbound.string("server"))
        assertEquals("443", outbound.string("server_port"))
        assertEquals("11111111-2222-3333-4444-555555555555", outbound.string("uuid"))
        val tls = outbound["tls"]!!.jsonObject
        assertEquals("true", tls.string("enabled"))
        assertEquals("example.com", tls.string("server_name"))
        val transport = outbound["transport"]!!.jsonObject
        assertEquals("ws", transport.string("type"))
        assertEquals("/ws", transport.string("path"))
        assertEquals("example.com", transport["headers"]!!.jsonObject.string("Host"))
    }

    @Test
    fun parsesVlessReality() {
        val link = "vless://uuid-1234@1.2.3.4:443?security=reality&sni=www.microsoft.com" +
            "&fp=chrome&pbk=PUBLIC_KEY&sid=ab12&flow=xtls-rprx-vision#reality"
        val outbound = SingBoxShareLink.parse(link)
        assertNotNull(outbound)
        assertEquals("xtls-rprx-vision", outbound.string("flow"))
        val tls = outbound["tls"]!!.jsonObject
        assertEquals("www.microsoft.com", tls.string("server_name"))
        assertEquals("chrome", tls["utls"]!!.jsonObject.string("fingerprint"))
        val reality = tls["reality"]!!.jsonObject
        assertEquals("PUBLIC_KEY", reality.string("public_key"))
        assertEquals("ab12", reality.string("short_id"))
    }

    @Test
    fun parsesVmess() {
        val payload = """{"v":"2","ps":"vmess node","add":"1.2.3.4","port":"443","id":"uuid-x","aid":"0","scy":"auto","net":"ws","type":"none","host":"a.com","path":"/p","tls":"tls","sni":"a.com"}"""
        val link = "vmess://" + payload.base64()
        val outbound = SingBoxShareLink.parse(link)
        assertNotNull(outbound)
        assertEquals("vmess", outbound.string("type"))
        assertEquals("vmess node", outbound.string("tag"))
        assertEquals("1.2.3.4", outbound.string("server"))
        assertEquals("443", outbound.string("server_port"))
        assertEquals("uuid-x", outbound.string("uuid"))
        assertEquals("auto", outbound.string("security"))
        assertEquals("true", outbound["tls"]!!.jsonObject.string("enabled"))
        assertEquals("ws", outbound["transport"]!!.jsonObject.string("type"))
    }

    @Test
    fun parsesShadowsocksWithBase64Credentials() {
        val credentials = "aes-256-gcm:pass".base64()
        val outbound = SingBoxShareLink.parse("ss://$credentials@1.2.3.4:8388#ss-node")
        assertNotNull(outbound)
        assertEquals("shadowsocks", outbound.string("type"))
        assertEquals("ss-node", outbound.string("tag"))
        assertEquals("aes-256-gcm", outbound.string("method"))
        assertEquals("pass", outbound.string("password"))
        assertEquals("1.2.3.4", outbound.string("server"))
        assertEquals("8388", outbound.string("server_port"))
    }

    @Test
    fun parsesShadowsocksWithFullyEncodedBody() {
        val encoded = "chacha20-ietf-poly1305:secret@example.org:443".base64()
        val outbound = SingBoxShareLink.parse("ss://$encoded#full")
        assertNotNull(outbound)
        assertEquals("chacha20-ietf-poly1305", outbound.string("method"))
        assertEquals("secret", outbound.string("password"))
        assertEquals("example.org", outbound.string("server"))
        assertEquals("443", outbound.string("server_port"))
    }

    @Test
    fun parsesTrojanWithGrpc() {
        val link = "trojan://password@example.com:443?security=tls&sni=example.com" +
            "&type=grpc&serviceName=grpcsvc#tj"
        val outbound = SingBoxShareLink.parse(link)
        assertNotNull(outbound)
        assertEquals("trojan", outbound.string("type"))
        assertEquals("password", outbound.string("password"))
        assertEquals("grpc", outbound["transport"]!!.jsonObject.string("type"))
        assertEquals("grpcsvc", outbound["transport"]!!.jsonObject.string("service_name"))
    }

    @Test
    fun parsesHysteria2WithObfs() {
        val link = "hysteria2://pass@1.2.3.4:8443?sni=example.com&insecure=1" +
            "&obfs=salamander&obfs-password=obfspass#hy"
        val outbound = SingBoxShareLink.parse(link)
        assertNotNull(outbound)
        assertEquals("hysteria2", outbound.string("type"))
        assertEquals("pass", outbound.string("password"))
        assertEquals("true", outbound["tls"]!!.jsonObject.string("insecure"))
        val obfs = outbound["obfs"]!!.jsonObject
        assertEquals("salamander", obfs.string("type"))
        assertEquals("obfspass", obfs.string("password"))
    }

    @Test
    fun ignoresUnsupportedLinks() {
        assertNull(SingBoxShareLink.parse("http://example.com"))
        assertNull(SingBoxShareLink.parse("not a link"))
        assertNull(SingBoxShareLink.parse(""))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}

@OptIn(ExperimentalEncodingApi::class)
class SingBoxSubscriptionParserTest {

    private fun String.base64(): String = Base64.encode(encodeToByteArray())

    private val vless = "vless://uuid-1@a.example.com:443?security=tls&sni=a.example.com#node-a"
    private val trojan = "trojan://pass@b.example.com:443?security=tls#node-b"

    @Test
    fun parsesPlainLinkList() {
        val result = SingBoxSubscriptionParser.parse(vless + "\n" + trojan)
        assertIs<SingBoxSubscriptionParser.Result.Success>(result)
        assertEquals(2, result.nodeCount)

        val config = Json.parseToJsonElement(result.configJson).jsonObject
        val outbounds = config["outbounds"]!!.jsonArray
        // 2 个节点 + selector + direct
        assertEquals(4, outbounds.size)
        assertEquals("proxy", config["route"]!!.jsonObject.string("final"))
        val selector = outbounds[2].jsonObject
        assertEquals("selector", selector.string("type"))
        assertEquals(2, selector["outbounds"]!!.jsonArray.size)
    }

    @Test
    fun parsesBase64EncodedLinkList() {
        val result = SingBoxSubscriptionParser.parse((vless + "\n" + trojan).base64())
        assertIs<SingBoxSubscriptionParser.Result.Success>(result)
        assertEquals(2, result.nodeCount)
    }

    @Test
    fun singleNodeIsUsedDirectly() {
        val result = SingBoxSubscriptionParser.parse(vless)
        assertIs<SingBoxSubscriptionParser.Result.Success>(result)
        assertEquals(1, result.nodeCount)
        val config = Json.parseToJsonElement(result.configJson).jsonObject
        val outbounds = config["outbounds"]!!.jsonArray
        assertEquals(2, outbounds.size)
        assertEquals("node-a", config["route"]!!.jsonObject.string("final"))
    }

    @Test
    fun keepsFullSingBoxConfig() {
        val raw = """{"outbounds":[{"type":"direct","tag":"direct"},{"type":"block","tag":"block"}]}"""
        val result = SingBoxSubscriptionParser.parse(raw)
        assertIs<SingBoxSubscriptionParser.Result.Success>(result)
        assertEquals(2, result.nodeCount)
    }

    @Test
    fun reportsEmptyContent() {
        val result = SingBoxSubscriptionParser.parse("   ")
        assertEquals(
            SingBoxSubscriptionParser.Reason.EMPTY,
            (result as SingBoxSubscriptionParser.Result.Failure).reason,
        )
    }

    @Test
    fun reportsUnrecognizedContent() {
        val result = SingBoxSubscriptionParser.parse("hello world!")
        assertTrue(result is SingBoxSubscriptionParser.Result.Failure)
    }

    @Test
    fun subscriptionConfigWorksWithLocalInboundRewrite() {
        val parsed = SingBoxSubscriptionParser.parse(vless + "\n" + trojan)
        assertIs<SingBoxSubscriptionParser.Result.Success>(parsed)
        val built = SingBoxConfig.buildLocalMixedConfig(parsed.configJson, 20800)
        assertIs<SingBoxConfig.Result.Success>(built)
        val config = Json.parseToJsonElement(built.content).jsonObject
        assertEquals(1, config["inbounds"]!!.jsonArray.size)
        assertEquals("mixed", config["inbounds"]!!.jsonArray.first().jsonObject.string("type"))
        assertEquals(4, config["outbounds"]!!.jsonArray.size)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
