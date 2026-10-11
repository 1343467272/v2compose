package io.github.v2compose.network

import io.github.v2compose.core.extension.isRedirect
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom

/**
 * sov2ex 搜索接口（第三方服务，非 V2EX 自身域名）。
 *
 * 该接口由 [V2exApi.search] 调用，但请求的是 `www.sov2ex.com`，
 * 因此不能沿用 V2 客户端的重定向策略（V2 客户端为了识别 V2EX 业务 302 而关闭了跟随），
 * 这里显式跟随重定向。
 */
internal const val SOV2EX_SEARCH_URL = "https://www.sov2ex.com/api/search"

/** 单次搜索请求的超时时间，避免弱网下长时间挂起（iOS Darwin 默认请求超时长达 60s）。 */
internal const val SOV2EX_SEARCH_TIMEOUT_MS = 15_000L

/** 搜索接口最多跟随的重定向次数。 */
internal const val SOV2EX_MAX_REDIRECTS = 3

/**
 * 搜索服务返回异常状态码或重定向异常时抛出。
 * message 会被展示到搜索结果页的错误提示中。
 */
internal class Sov2exSearchException(message: String) : Exception(message)

/** 构造搜索请求地址，包含 q/from/size 三个查询参数。 */
internal fun buildSov2exSearchUrl(keyword: String, from: Int, size: Int): String =
    URLBuilder(SOV2EX_SEARCH_URL).apply {
        parameters.append("q", keyword)
        parameters.append("from", from.toString())
        parameters.append("size", size.toString())
    }.buildString()

/**
 * 解析重定向地址，规则与 Ktor 的 HttpRedirect 插件一致：
 * 清空原查询参数后用 [URLBuilder.takeFrom] 合并，因此同时支持绝对地址与相对地址。
 */
internal fun resolveSearchRedirectUrl(currentUrl: String, location: String): String {
    val builder = URLBuilder(currentUrl)
    builder.parameters.clear()
    builder.takeFrom(location)
    return builder.buildString()
}

/**
 * 发起 GET 请求并跟随 3xx 重定向，返回最终的成功响应。
 *
 * - 每个请求都带 [timeoutMillis] 超时，避免请求无限挂起；
 * - 3xx 手动跟随（V2 客户端关闭了自动跟随），最多 [SOV2EX_MAX_REDIRECTS] 次；
 * - 非 2xx 时抛出携带状态码与服务端 message 的 [Sov2exSearchException]，
 *   便于搜索结果页展示可读的失败原因并让用户重试。
 */
internal suspend fun HttpClient.getFollowingRedirects(
    url: String,
    timeoutMillis: Long = SOV2EX_SEARCH_TIMEOUT_MS,
): HttpResponse {
    var currentUrl = url
    var redirectCount = 0
    while (true) {
        val response = get(currentUrl) {
            expectSuccess = false
            timeout { requestTimeoutMillis = timeoutMillis }
        }
        val location = response.headers[HttpHeaders.Location]
        if (response.status.value.isRedirect && !location.isNullOrBlank()) {
            if (redirectCount >= SOV2EX_MAX_REDIRECTS) {
                throw Sov2exSearchException("搜索服务重定向次数过多")
            }
            redirectCount++
            currentUrl = resolveSearchRedirectUrl(currentUrl, location)
            continue
        }
        if (!response.status.isSuccess()) {
            val detail = runCatching { response.bodyAsText() }.getOrDefault("").trim().take(200)
            val suffix = if (detail.isEmpty()) "" else "：" + detail
            throw Sov2exSearchException("搜索服务返回 HTTP " + response.status.value + suffix)
        }
        return response
    }
}
