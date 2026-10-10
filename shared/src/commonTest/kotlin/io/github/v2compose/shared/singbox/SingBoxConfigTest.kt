package io.github.v2compose.shared.singbox

import io.github.v2compose.shared.bean.ProxyInfo
import io.github.v2compose.shared.bean.ProxyType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SingBoxConfigTest {

    private val validConfig = """
        {
          "log": { "level": "warn" },
          "inbounds": [
            { "type": "tun", "tag": "tun-in", "auto_route": true }
          ],
          "outbounds": [
            { "type": "vless", "tag": "proxy", "server": "example.com", "server_port": 443, "uuid": "00000000-0000-0000-0000-000000000000" },
            { "type": "direct", "tag": "direct" }
          ],
          "route": {
            "auto_detect_interface": true,
            "rules": [
              { "inbound": "tun-in", "outbound": "direct" },
              { "domain": ["example.org"], "outbound": "proxy" }
            ],
            "final": "proxy"
          }
        }
    """.trimIndent()

    @Test
    fun rejectsBlankConfig() {
        assertEquals(SingBoxConfig.Error.EMPTY, SingBoxConfig.validate("  "))
        assertEquals(SingBoxConfig.Error.EMPTY, SingBoxConfig.validate(""))
    }

    @Test
    fun rejectsBrokenJson() {
        assertEquals(SingBoxConfig.Error.INVALID_JSON, SingBoxConfig.validate("{ not json"))
    }

    @Test
    fun rejectsNonObjectConfig() {
        assertEquals(SingBoxConfig.Error.NOT_OBJECT, SingBoxConfig.validate("[1, 2, 3]"))
    }

    @Test
    fun rejectsConfigWithoutOutbounds() {
        assertEquals(SingBoxConfig.Error.NO_OUTBOUND, SingBoxConfig.validate("""{"log": {}}"""))
        assertEquals(SingBoxConfig.Error.NO_OUTBOUND, SingBoxConfig.validate("""{"outbounds": []}"""))
    }

    @Test
    fun acceptsValidConfig() {
        assertEquals(null, SingBoxConfig.validate(validConfig))
    }

    @Test
    fun replacesInboundsWithLocalMixedInbound() {
        val result = SingBoxConfig.buildLocalMixedConfig(validConfig, 20800)
        assertIs<SingBoxConfig.Result.Success>(result)
        assertEquals(2, result.outboundCount)

        val config = Json.parseToJsonElement(result.content).jsonObject
        val inbounds = config["inbounds"]!!.jsonArray
        assertEquals(1, inbounds.size)
        val inbound = inbounds.first().jsonObject
        assertEquals("mixed", inbound["type"]!!.jsonPrimitive.content)
        assertEquals(SingBoxConfig.MIXED_INBOUND_TAG, inbound["tag"]!!.jsonPrimitive.content)
        assertEquals(SingBoxConfig.LOCAL_ADDRESS, inbound["listen"]!!.jsonPrimitive.content)
        assertEquals(20800, inbound["listen_port"]!!.jsonPrimitive.int)

        // tun 入站被移除，因此不需要 VPN 权限
        assertFalse(result.content.contains("tun-in"))
    }

    @Test
    fun keepsOutboundsAndDisablesAutoDetectInterface() {
        val result = SingBoxConfig.buildLocalMixedConfig(validConfig, 20800)
        val config = Json.parseToJsonElement(
            (result as SingBoxConfig.Result.Success).content
        ).jsonObject

        assertEquals(2, config["outbounds"]!!.jsonArray.size)

        val route = config["route"]!!.jsonObject
        assertFalse(route["auto_detect_interface"]!!.jsonPrimitive.boolean)
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)

        // 引用了已被替换掉的 tun-in 的规则应当被移除
        val rules = route["rules"]!!.jsonArray
        assertEquals(1, rules.size)
        assertTrue(rules.first().jsonObject.containsKey("domain"))
    }

    @Test
    fun keepsRouteWithoutRules() {
        val config = """{"outbounds": [{"type": "direct", "tag": "direct"}]}"""
        val result = SingBoxConfig.buildLocalMixedConfig(config, 1234)
        val parsed = Json.parseToJsonElement(
            (result as SingBoxConfig.Result.Success).content
        ).jsonObject
        val route = parsed["route"]!!.jsonObject
        assertFalse(route["auto_detect_interface"]!!.jsonPrimitive.boolean)
        assertFalse(route.containsKey("rules"))
    }
}

class ProxyInfoSingBoxTest {

    @Test
    fun keepsSingBoxConfigThroughSerialization() {
        val info = ProxyInfo(
            type = ProxyType.SingBox,
            singboxConfig = """{"outbounds": []}""",
        )
        val restored = ProxyInfo.fromJson(info.toJson())
        assertEquals(ProxyType.SingBox, restored.type)
        assertEquals(info.singboxConfig, restored.singboxConfig)
    }

    @Test
    fun readsLegacyProxyInfoWithoutSingBoxField() {
        val restored = ProxyInfo.fromJson("""{"type":"Http","address":"127.0.0.1","port":7890}""")
        assertEquals(ProxyType.Http, restored.type)
        assertEquals("127.0.0.1", restored.address)
        assertEquals(7890, restored.port)
        assertEquals("", restored.singboxConfig)
    }

    @Test
    fun defaultProxyIsDirectWithoutConfig() {
        assertEquals(ProxyType.Direct, ProxyInfo.Default.type)
        assertEquals("", ProxyInfo.Default.singboxConfig)
        assertNotNull(ProxyInfo.Default)
    }
}
