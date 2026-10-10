package io.github.v2compose.di

import android.os.Build
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import io.github.v2compose.PlatformCapabilities
import io.github.v2compose.core.CheckInWorker
import io.github.v2compose.datasource.createAccountDataStore
import io.github.v2compose.datasource.createAppDataStore
import io.github.v2compose.network.CookieManager
import io.github.v2compose.network.HttpCacheManager
import io.github.v2compose.network.NetworkClientProvider
import io.github.v2compose.network.OkHttpFactory
import io.github.v2compose.network.OkHttpCacheManager
import io.github.v2compose.network.ProxyManager
import io.github.v2compose.network.SingBoxProxyController
import io.github.v2compose.network.WebkitCookieManager
import io.github.v2compose.network.createAndroidImageHttpClient
import io.github.v2compose.network.AndroidNetworkClientProvider
import io.github.v2compose.network.createAndroidGithubHttpClient
import io.github.v2compose.network.createAndroidV2HttpClient
import io.github.v2compose.network.di.V2ProxySelector
import io.github.v2compose.shared.core.V2EventManager
import io.github.v2compose.singbox.AndroidSingBoxProxyController
import io.github.v2compose.ui.main.AndroidAutoCheckInScheduler
import io.github.v2compose.ui.main.AndroidWebViewProxyController
import io.github.v2compose.ui.main.AutoCheckInScheduler
import io.github.v2compose.ui.main.WebViewProxyController
import io.github.v2compose.usecase.FixHtmlUseCase
import io.github.v2compose.usecase.HtmlImageLoader
import io.ktor.client.HttpClient
import okhttp3.OkHttpClient
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalCoilApi::class)
actual val platformModule: Module = module {
    single { PlatformCapabilities.Android }
    single(named("Account")) { createAccountDataStore(get()) }
    single(named("App")) { createAppDataStore(get()) }

    // Core/App
    single<ExecutorService> { Executors.newFixedThreadPool(4) }
    single<DiskCache> {
        val dir = File(get<android.content.Context>().cacheDir, "image_cache")
        DiskCache.Builder().directory(dir).maxSizePercent(0.02).build()
    }
    single<ImageLoader> {
        ImageLoader.Builder(get<android.content.Context>())
            .components {
                add(KtorNetworkFetcherFactory(get<HttpClient>(named("ImageHttpClient"))))
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
            }
            .diskCache(get<DiskCache>())
            .build()
    }
    single<AutoCheckInScheduler> { AndroidAutoCheckInScheduler(get()) }
    single<SingBoxProxyController> { AndroidSingBoxProxyController(get()) }
    single<WebViewProxyController> { AndroidWebViewProxyController(get(), get()) }

    // Network
    single<io.github.fruit.Fruit> { OkHttpFactory.createFruit() }

    single { WebkitCookieManager() }
    single<okhttp3.CookieJar> { get<WebkitCookieManager>() }
    single<CookieManager> { get<WebkitCookieManager>() }

    singleOf(::V2ProxySelector)
    single<ProxyManager> { get<V2ProxySelector>() }
    single<okhttp3.Cache> { OkHttpFactory.createCache(get<android.content.Context>()) }
    single<HttpCacheManager> { OkHttpCacheManager(get()) }

    single<OkHttpClient>(named("CommonOkHttpClient")) {
        OkHttpFactory.createHttpClient(
            get<okhttp3.CookieJar>(),
            get<okhttp3.Cache>(),
            get<V2ProxySelector>(),
        )
    }
    single<OkHttpClient>(named("ImageOkHttpClient")) {
        OkHttpFactory.createImageHttpClient(get<okhttp3.CookieJar>(), get<V2ProxySelector>())
    }

    single<HttpClient>(named("V2HttpClient")) {
        createAndroidV2HttpClient(
            okHttpClient = get<OkHttpClient>(named("CommonOkHttpClient")),
            fruit = get<io.github.fruit.Fruit>(),
            eventManager = get<V2EventManager>(),
        )
    }

    single<HttpClient>(named("ImageHttpClient")) {
        createAndroidImageHttpClient(
            okHttpClient = get<OkHttpClient>(named("ImageOkHttpClient")),
        )
    }

    single<HttpClient>(named("GithubHttpClient")) {
        createAndroidGithubHttpClient(okHttpClient = get<OkHttpClient>(named("CommonOkHttpClient")))
    }
    single<NetworkClientProvider> {
        AndroidNetworkClientProvider(
            v2HttpClient = get(named("V2HttpClient")),
            imageHttpClient = get(named("ImageHttpClient")),
            githubHttpClient = get(named("GithubHttpClient")),
        )
    }

    // UseCase
    singleOf(::FixHtmlUseCase)
    single<HtmlImageLoader> { get<FixHtmlUseCase>() }

    // Worker
    workerOf(::CheckInWorker)
}
