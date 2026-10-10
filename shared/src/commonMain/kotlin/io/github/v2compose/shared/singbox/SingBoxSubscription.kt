package io.github.v2compose.shared.singbox

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 订阅内容解析。
 *
 * 支持三种订阅内容：
 * 1. 完整的 sing-box config JSON（含 outbounds）；
 * 2. 正文为若干分享链接（每行一条）；
 * 3. 上述链接列表的 Base64 编码（很多机场的通用订阅格式）。
 *
 * 解析结果是一份新的 sing-box config JSON，inbounds 稍后由
 * [SingBoxConfig.buildLocalMixedConfig] 统一替换为本地 mixed 入站。
 */
@OptIn(ExperimentalSerializationApi::class)
object SingBoxSubscriptionParser {

    enum class Reason {
        /** 订阅内容为空 */
        EMPTY,

        /** 没有解析出任何可用节点 */
        NO_NODES,

        /** 内容既不是 JSON 也不是可识别的链接列表 */
        UNRECOGNIZED,
    }

    sealed interface Result {
        data class Success(val configJson: String, val nodeCount: Int) : Result
        data class Failure(val reason: Reason) : Result
    }

    const val SELECTOR_TAG = "proxy"
    const val DIRECT_TAG = "direct"

    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun parse(content: String): Result {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Result.Failure(Reason.EMPTY)

        // 1. 完整的 sing-box 配置
        val config = parseSingBoxConfig(trimmed)
        if (config != null) {
            val outbounds = config["outbounds"] as? JsonArray
            if (outbounds != null && outbounds.isNotEmpty()) {
                return Result.Success(
                    prettyJson.encodeToString(JsonObject.serializer(), config),
                    outbounds.size,
                )
            }
        }

        // 2/3. 分享链接列表（可能是 base64 编码的）
        val text = if (trimmed.contains("://")) {
            trimmed
        } else {
            SingBoxShareLink.decodeBase64(trimmed) ?: return Result.Failure(Reason.UNRECOGNIZED)
        }

        val outbounds = buildList {
            text.lineSequence()
                .map { it.trim() }
                .filter { it.contains("://") }
                .forEachIndexed { index, line ->
                    SingBoxShareLink.parse(line, index)?.let { add(it) }
                }
        }
        if (outbounds.isEmpty()) {
            return Result.Failure(Reason.NO_NODES)
        }
        return Result.Success(buildSubscriptionConfig(outbounds), outbounds.size)
    }

    private fun parseSingBoxConfig(text: String): JsonObject? {
        if (!text.startsWith("{")) return null
        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    }

    private fun buildSubscriptionConfig(outbounds: List<JsonObject>): String {
        val tags = outbounds.mapIndexed { index, outbound ->
            (outbound["tag"] as? JsonPrimitive)?.content ?: "node-" + index
        }
        val config = buildJsonObject {
            put("outbounds", buildJsonArray {
                addAll(outbounds)
                if (outbounds.size == 1) {
                    add(buildJsonObject {
                        put("type", "direct")
                        put("tag", DIRECT_TAG)
                    })
                } else {
                    add(buildJsonObject {
                        put("type", "selector")
                        put("tag", SELECTOR_TAG)
                        put("outbounds", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
                        put("default", tags.first())
                    })
                    add(buildJsonObject {
                        put("type", "direct")
                        put("tag", DIRECT_TAG)
                    })
                }
            })
            put("route", buildJsonObject {
                put("final", if (outbounds.size == 1) tags.first() else SELECTOR_TAG)
            })
        }
        return prettyJson.encodeToString(JsonObject.serializer(), config)
    }
}
