package io.github.v2compose.di

import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.PlatformContext as CoilPlatformContext
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import io.github.fruit.Fruit
import io.github.fruit.registerGeneratedSliceAdapters
import io.github.v2compose.PlatformCapabilities
import io.github.v2compose.core.PlatformContext as AppPlatformContext
import io.github.v2compose.datasource.createAccountDataStore
import io.github.v2compose.datasource.createAppDataStore
import io.github.v2compose.network.CookieManager
import io.github.v2compose.network.HttpCacheManager
import io.github.v2compose.network.IosHttpCacheManager
import io.github.v2compose.network.IosCookieManager
import io.github.v2compose.network.IosNetworkClientRegistry
import io.github.v2compose.network.NetworkClientProvider
import io.github.v2compose.network.NoOpSingBoxProxyController
import io.github.v2compose.network.ProxyManager
import io.github.v2compose.network.SingBoxProxyController
import io.github.v2compose.ui.main.AutoCheckInScheduler
import io.github.v2compose.ui.main.IosAutoCheckInScheduler
import io.github.v2compose.ui.main.IosWebViewProxyController
import io.github.v2compose.ui.main.WebViewProxyController
import io.github.v2compose.usecase.FixHtmlUseCase
import io.github.v2compose.usecase.HtmlImageLoader
import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURLCache
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalCoilApi::class)
actual val platformModule: Module = module {
    single { PlatformCapabilities.Ios }
    single<AppPlatformContext> { IosAppPlatformContext }
    single<CoilPlatformContext> { CoilPlatformContext.INSTANCE }

    single(named("Account")) { createAccountDataStore(get<AppPlatformContext>()) }
    single(named("App")) { createAppDataStore(get<AppPlatformContext>()) }

    single<DiskCache> {
        DiskCache.Builder()
            .directory(iosCacheDirectory("image_cache"))
            .maxSizePercent(0.02)
            .build()
    }
    single<NSURLCache> {
        NSURLCache(
            memoryCapacity = 8uL * 1024uL * 1024uL,
            diskCapacity = 32uL * 1024uL * 1024uL,
            diskPath = "v2compose_http_cache",
        )
    }
    single<ImageLoader> {
        ImageLoader.Builder(get<CoilPlatformContext>())
            .components {
                add(KtorNetworkFetcherFactory(get<HttpClient>(named("ImageHttpClient"))))
                add(SvgDecoder.Factory())
            }
            .diskCache(get<DiskCache>())
            .build()
    }

    single<AutoCheckInScheduler> { IosAutoCheckInScheduler() }
    single<WebViewProxyController> { IosWebViewProxyController() }
    single<SingBoxProxyController> { NoOpSingBoxProxyController() }
    single<Fruit> {
        Fruit().apply {
            registerGeneratedSliceAdapters()
        }
    }
    single<CookieManager> { IosCookieManager() }
    single<IosNetworkClientRegistry> { IosNetworkClientRegistry(get(), get(), get(), get()) }
    single<NetworkClientProvider> { get<IosNetworkClientRegistry>() }
    single<ProxyManager> { get<IosNetworkClientRegistry>() }
    single<HttpCacheManager> { IosHttpCacheManager(get()) }
    single<HttpClient>(named("ImageHttpClient")) {
        get<IosNetworkClientRegistry>().imageHttpClient()
    }

    singleOf(::FixHtmlUseCase)
    single<HtmlImageLoader> { get<FixHtmlUseCase>() }
}

private object IosAppPlatformContext : AppPlatformContext()

@OptIn(ExperimentalForeignApi::class)
private fun iosCacheDirectory(child: String) = buildString {
    val cacheDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSCachesDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    requireNotNull(cacheDirectory?.path) { "Failed to resolve iOS cache directory." }
    append(cacheDirectory.path)
    append('/')
    append(child)
}.toPath()
