package io.github.v2compose.shared.bean

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProxyInfo(
    val type: ProxyType = ProxyType.Direct,
    val address: String = "",
    val port: Int = 0,
    /**
     * sing-box 配置（完整的 sing-box config JSON）。
     * 仅当 [type] 为 [ProxyType.SingBox] 时使用，由 App 内嵌的 sing-box 核心加载。
     */
    val singboxConfig: String = "",
    /** sing-box 订阅链接；为空表示手动配置 */
    val singboxSubscriptionUrl: String = "",
    /** 订阅最近一次更新时间（毫秒），用于自动更新判断 */
    val singboxSubscriptionUpdatedAt: Long = 0,
) {
    companion object {
        val Default = ProxyInfo()

        fun fromJson(json: String): ProxyInfo {
            return runCatching { Json.decodeFromString<ProxyInfo>(json) }.getOrDefault(Default)
        }
    }

    fun toJson(): String {
        return Json.encodeToString(this)
    }
}

@Serializable
enum class ProxyType {
    System,
    Direct,
    Http,
    Socks,

    /** 使用 App 内嵌的 sing-box 核心（本地 mixed 入站）作为代理 */
    SingBox,
}
