package io.github.v2compose.network

import io.github.fruit.Fruit
import io.github.v2compose.datasource.AppPreferences
import io.github.v2compose.shared.bean.ProxyInfo
import io.github.v2compose.shared.bean.ProxyType
import io.github.v2compose.shared.core.V2EventManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Url
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURLCache
import platform.Foundation.NSURLRequestUseProtocolCachePolicy

class IosNetworkClientRegistry(
    private val fruit: Fruit,
    private val urlCache: NSURLCache,
    appPreferences: AppPreferences,
    private val eventManager: V2EventManager,
) : NetworkClientProvider, ProxyManager {
    private var currentProxyInfo: ProxyInfo = runBlocking { appPreferences.proxyInfo.first() }
    private var v2HttpClient: HttpClient = buildV2HttpClient(currentProxyInfo)
    private var imageHttpClient: HttpClient = buildImageHttpClient(currentProxyInfo)
    private var githubHttpClient: HttpClient = buildGithubHttpClient(currentProxyInfo)

    override fun v2HttpClient(): HttpClient = v2HttpClient

    override fun imageHttpClient(): HttpClient = imageHttpClient

    override fun githubHttpClient(): HttpClient = githubHttpClient

    override fun updateProxy(proxyInfo: ProxyInfo) {
        if (proxyInfo == currentProxyInfo) return

        val oldV2HttpClient = v2HttpClient
        val oldImageHttpClient = imageHttpClient
        val oldGithubHttpClient = githubHttpClient

        currentProxyInfo = proxyInfo
        v2HttpClient = buildV2HttpClient(proxyInfo)
        imageHttpClient = buildImageHttpClient(proxyInfo)
        githubHttpClient = buildGithubHttpClient(proxyInfo)

        oldV2HttpClient.close()
        oldImageHttpClient.close()
        oldGithubHttpClient.close()
    }

    private fun buildV2HttpClient(proxyInfo: ProxyInfo): HttpClient =
        createV2HttpClient(
            engine = createIosHttpClientEngine(
                proxyInfo = proxyInfo,
                urlCache = urlCache,
                useSharedCookieStorage = true,
            ),
            fruit = fruit,
            eventManager = eventManager,
        )

    private fun buildImageHttpClient(proxyInfo: ProxyInfo): HttpClient =
        createImageHttpClient(
            engine = createIosHttpClientEngine(
                proxyInfo = proxyInfo,
                urlCache = urlCache,
                useSharedCookieStorage = true,
            ),
        )

    private fun buildGithubHttpClient(proxyInfo: ProxyInfo): HttpClient =
        createGithubHttpClient(
            engine = createIosHttpClientEngine(
                proxyInfo = proxyInfo,
                urlCache = urlCache,
                useSharedCookieStorage = false,
            ),
        )
}

private fun createIosHttpClientEngine(
    proxyInfo: ProxyInfo,
    urlCache: NSURLCache,
    useSharedCookieStorage: Boolean,
): HttpClientEngine = Darwin.create {
    configureSession {
        URLCache = urlCache
        requestCachePolicy = NSURLRequestUseProtocolCachePolicy
        if (useSharedCookieStorage) {
            HTTPCookieStorage = NSHTTPCookieStorage.sharedHTTPCookieStorage
            HTTPShouldSetCookies = true
        }
    }

    proxyInfo.toProxyConfig()?.let { proxy = it }
}

private fun ProxyInfo.toProxyConfig() = when (type) {
    ProxyType.System,
    ProxyType.Direct,
    // iOS 暂不支持内嵌 sing-box 核心（需要 NetworkExtension），按直连处理
    ProxyType.SingBox,
    -> null

    ProxyType.Http -> ProxyBuilder.http(Url("http://$address:$port"))
    ProxyType.Socks -> ProxyBuilder.socks(host = address, port = port)
}
