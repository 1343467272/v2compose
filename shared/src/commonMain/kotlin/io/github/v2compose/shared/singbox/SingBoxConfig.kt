package io.github.v2compose.shared.singbox

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * sing-box 配置处理。
 *
 * V2compose 内嵌的 sing-box 核心运行在 App 进程内，并且只提供本地 mixed 入站
 * （即 127.0.0.1 上的 HTTP + SOCKS5 端口），App 自身流量通过该端口转发，
 * 因此不需要 TUN / VpnService 权限。
 *
 * 用户提供一份普通的 sing-box config JSON；启动前会被改造成：
 * - `inbounds` 被替换为唯一的本地 mixed 入站；
 * - `route.auto_detect_interface` 关闭（非 VPN 场景由系统决定选路）；
 * - 指向原入站（如 tun-in）的路由规则会被移除，避免核心启动时报“入站不存在”。
 */
@OptIn(ExperimentalSerializationApi::class)
object SingBoxConfig {

    const val LOCAL_ADDRESS = "127.0.0.1"

    /** 本地 mixed（HTTP + SOCKS5）入站 tag */
    const val MIXED_INBOUND_TAG = "mixed-in"

    enum class Error {
        /** 配置为空 */
        EMPTY,

        /** 不是合法的 JSON */
        INVALID_JSON,

        /** 顶层不是 JSON 对象 */
        NOT_OBJECT,

        /** 缺少可用的 outbounds */
        NO_OUTBOUND,
    }

    sealed interface Result {
        data class Success(val content: String, val outboundCount: Int) : Result
        data class Failure(val error: Error) : Result
    }

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** 只做结构校验，不生成最终配置。 */
    fun validate(rawConfig: String): Error? {
        return when (val result = build(rawConfig, 0)) {
            is Result.Failure -> result.error
            is Result.Success -> null
        }
    }

    /** 生成可供内嵌核心加载的配置，本地 mixed 入站监听 127.0.0.1:[listenPort]。 */
    fun buildLocalMixedConfig(rawConfig: String, listenPort: Int): Result {
        return build(rawConfig, listenPort)
    }

    private fun build(rawConfig: String, listenPort: Int): Result {
        val raw = rawConfig.trim()
        if (raw.isEmpty()) {
            return Result.Failure(Error.EMPTY)
        }
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            ?: return Result.Failure(Error.INVALID_JSON)
        val config = element as? JsonObject ?: return Result.Failure(Error.NOT_OBJECT)

        val outbounds = config["outbounds"] as? JsonArray
        if (outbounds == null || outbounds.isEmpty()) {
            return Result.Failure(Error.NO_OUTBOUND)
        }

        val mixedInbound = buildJsonObject {
            put("type", JsonPrimitive("mixed"))
            put("tag", JsonPrimitive(MIXED_INBOUND_TAG))
            put("listen", JsonPrimitive(LOCAL_ADDRESS))
            put("listen_port", JsonPrimitive(listenPort))
        }

        val route = (config["route"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        route["auto_detect_interface"] = JsonPrimitive(false)
        (route["rules"] as? JsonArray)?.let { rules ->
            route["rules"] = JsonArray(rules.filterNot { rule -> refersToOtherInbound(rule) })
        }

        val transformed = config.toMutableMap().apply {
            put("inbounds", JsonArray(listOf(mixedInbound)))
            put("route", JsonObject(route))
        }

        val content = prettyJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(transformed),
        )
        return Result.Success(content, outbounds.size)
    }

    /**
     * 该路由规则是否显式引用了除本地 mixed 入站以外的入站。
     * 这类规则在替换 inbounds 之后会让核心启动失败，需要剔除。
     */
    private fun refersToOtherInbound(rule: JsonElement): Boolean {
        val inbound = (rule as? JsonObject)?.get("inbound") ?: return false
        return runCatching { inbound.jsonPrimitive.content }.getOrNull() != MIXED_INBOUND_TAG
    }
}
