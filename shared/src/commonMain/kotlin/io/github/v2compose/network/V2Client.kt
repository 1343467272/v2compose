package io.github.v2compose.network

import io.github.fruit.Fruit
import io.github.fruit.ktor.fruit
import io.github.v2compose.Constants
import io.github.v2compose.authFlowRouteKey
import io.github.v2compose.isSameAuthFlow
import io.github.v2compose.core.extension.fullUrl
import io.github.v2compose.shared.bean.AuthRedirectEvent
import io.github.v2compose.shared.core.V2EventManager
import io.github.v2compose.util.KLogger
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.LoggingFormat
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal const val DEFAULT_IMAGE_ACCEPT_HEADER =
    "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"

/**
 * Shared Ktor Client for V2EX
 */
fun createV2HttpClient(
    engine: HttpClientEngine? = null,
    fruit: Fruit = Fruit.createDefault(),
    eventManager: V2EventManager? = null,
): HttpClient = createConfiguredHttpClient(
    engine = engine,
    eventManager = eventManager,
    installContentNegotiation = {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
            fruit(fruit)
        }
    },
    configureDefaultRequest = {
        url {
            protocol = URLProtocol.HTTPS
            host = "www.v2ex.com"
        }
        if (!headers.contains(NetConstants.keyUserAgent)) {
            header(HttpHeaders.UserAgent, NetConstants.wapUserAgent)
        }
    },
)

fun createImageHttpClient(
    engine: HttpClientEngine? = null,
): HttpClient = createConfiguredHttpClient(
    engine = engine,
    eventManager = null,
    installContentNegotiation = null,
    configureDefaultRequest = {
        if (!headers.contains(NetConstants.keyUserAgent)) {
            header(HttpHeaders.UserAgent, NetConstants.wapUserAgent)
        }
        if (!headers.contains(HttpHeaders.Accept)) {
            header(HttpHeaders.Accept, DEFAULT_IMAGE_ACCEPT_HEADER)
        }
    },
)

fun createGithubHttpClient(engine: HttpClientEngine? = null): HttpClient =
    HttpClient(engine ?: createHttpClientEngine()) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

private fun createConfiguredHttpClient(
    engine: HttpClientEngine?,
    eventManager: V2EventManager?,
    installContentNegotiation: (HttpClientConfig<*>.() -> Unit)?,
    configureDefaultRequest: DefaultRequest.DefaultRequestBuilder.() -> Unit,
): HttpClient = HttpClient(engine ?: createHttpClientEngine()) {
    expectSuccess = true

    // V2EX 的业务动作（感谢/收藏/忽略/关注等）以及登录/签到成功时都靠 302 表达结果。
    // 这里必须关闭自动跟随，让 3xx 以 ResponseException 的形式抛给上层 isRedirect 逻辑；
    // 否则请求会被跟随到目标 HTML 页面，ContentNegotiation 再去用 Fruit 解析 V2exResult 时
    // 就会抛出 "Fruit cannot handle class ...V2exResult. No factory found."。
    followRedirects = false

    installContentNegotiation?.invoke(this)

    defaultRequest {
        configureDefaultRequest.invoke(this)
    }

    if (eventManager != null) {
        ResponseObserver { response ->
            handleAuthRedirectResponse(response, eventManager)
        }
    }

    install(Logging) {
        logger = Logger.SIMPLE
        level = LogLevel.HEADERS
        format = LoggingFormat.OkHttp
    }
}

internal fun resolveAuthRedirectEventLocation(
    requestUrl: String,
    redirectLocation: String?,
): String? {
    if (redirectLocation.isNullOrBlank()) return null
    if (!isV2exRoute(requestUrl) || !isV2exRoute(redirectLocation)) return null
    if (isSameAuthFlow(requestUrl, redirectLocation)) return null
    val requestIsAuthFlow = authFlowRouteKey(requestUrl) != null
    val redirectIsAuthFlow = authFlowRouteKey(redirectLocation) != null
    if (!requestIsAuthFlow && !redirectIsAuthFlow) return null
    return redirectLocation
}

private fun isV2exRoute(raw: String): Boolean {
    val normalized = raw.fullUrl(Constants.baseUrl)
    val host = runCatching { Url(normalized).host }.getOrNull() ?: return false
    return host.endsWith(Constants.host)
}

private suspend fun handleAuthRedirectResponse(
    response: io.ktor.client.statement.HttpResponse,
    eventManager: V2EventManager?,
) {
    if (eventManager == null) return
    if (response.status.value !in 300..399) return
    val requestUrl = response.call.request.url.toString()
    val redirectLocation = response.headers[HttpHeaders.Location]
    KLogger.d(
        "V2Client",
        "redirect observed: request=$requestUrl, location=$redirectLocation, status=${response.status.value}",
    )
    resolveAuthRedirectEventLocation(
        requestUrl = requestUrl,
        redirectLocation = redirectLocation,
    )?.let {
        KLogger.d("V2Client", "post AuthRedirectEvent($it)")
        eventManager.tryPost(AuthRedirectEvent(it))
    }
}
