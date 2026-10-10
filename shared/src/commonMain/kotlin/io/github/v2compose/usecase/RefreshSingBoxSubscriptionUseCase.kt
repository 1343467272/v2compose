package io.github.v2compose.usecase

import io.github.v2compose.datasource.AppPreferences
import io.github.v2compose.network.SingBoxSubscriptionFetcher
import io.github.v2compose.shared.bean.ProxyType
import io.github.v2compose.shared.singbox.SingBoxSubscriptionParser
import io.github.v2compose.util.currentTimeMillis
import kotlinx.coroutines.flow.first

/**
 * 导入 / 自动更新 sing-box 订阅。
 *
 * 导入结果会写回 [AppPreferences.proxyInfo]，网络层与内嵌核心会随之自动刷新。
 */
class RefreshSingBoxSubscriptionUseCase(
    private val appPreferences: AppPreferences,
    private val fetcher: SingBoxSubscriptionFetcher,
) {

    enum class FailureReason {
        EMPTY_URL,
        NETWORK,
        EMPTY_CONTENT,
        NO_NODES,
        UNRECOGNIZED,
    }

    sealed interface Result {
        data class Success(val nodeCount: Int) : Result
        data class Failed(val reason: FailureReason, val message: String? = null) : Result
        data object Skipped : Result
    }

    /** 应用启动时调用：订阅过期（默认 6 小时）才重新拉取。 */
    suspend fun refreshIfNeeded(): Result {
        val current = appPreferences.proxyInfo.first()
        val url = current.singboxSubscriptionUrl.trim()
        if (current.type != ProxyType.SingBox || url.isEmpty()) return Result.Skipped
        val elapsed = currentTimeMillis() - current.singboxSubscriptionUpdatedAt
        if (elapsed in 0 until UPDATE_INTERVAL_MS) return Result.Skipped
        return import(url)
    }

    /** 立即拉取并解析订阅链接。 */
    suspend fun import(url: String): Result {
        val target = url.trim()
        if (target.isEmpty()) return Result.Failed(FailureReason.EMPTY_URL)

        val content = try {
            fetcher.fetch(target)
        } catch (t: Throwable) {
            return Result.Failed(FailureReason.NETWORK, t.message)
        }

        val parsed = SingBoxSubscriptionParser.parse(content)
        if (parsed is SingBoxSubscriptionParser.Result.Failure) {
            val reason = when (parsed.reason) {
                SingBoxSubscriptionParser.Reason.EMPTY -> FailureReason.EMPTY_CONTENT
                SingBoxSubscriptionParser.Reason.NO_NODES -> FailureReason.NO_NODES
                SingBoxSubscriptionParser.Reason.UNRECOGNIZED -> FailureReason.UNRECOGNIZED
            }
            return Result.Failed(reason)
        }
        parsed as SingBoxSubscriptionParser.Result.Success

        val current = appPreferences.proxyInfo.first()
        appPreferences.proxyInfo(
            current.copy(
                type = ProxyType.SingBox,
                singboxConfig = parsed.configJson,
                singboxSubscriptionUrl = target,
                singboxSubscriptionUpdatedAt = currentTimeMillis(),
            )
        )
        return Result.Success(parsed.nodeCount)
    }

    companion object {
        /** 订阅自动更新间隔：6 小时 */
        const val UPDATE_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
