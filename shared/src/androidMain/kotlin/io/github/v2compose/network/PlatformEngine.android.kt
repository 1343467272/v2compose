package io.github.v2compose.network

import io.github.fruit.Fruit
import io.github.v2compose.shared.core.V2EventManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

// 由 Ktor 的 HttpRedirect 插件统管是否跟随重定向（与各客户端的 followRedirects 策略一致），
// 避免引擎层自行跟随导致业务 302 无法暴露给上层 isRedirect 逻辑。
actual fun createHttpClientEngine(): HttpClientEngine = OkHttp.create {
    config {
        followRedirects(false)
        followSslRedirects(false)
    }
}

fun createAndroidV2HttpClient(
    okHttpClient: OkHttpClient,
    fruit: Fruit = Fruit.createDefault(),
    eventManager: V2EventManager? = null,
): HttpClient {
    val engine = OkHttp.create {
        preconfigured = okHttpClient
    }
    return createV2HttpClient(engine, fruit, eventManager)
}

fun createAndroidImageHttpClient(okHttpClient: OkHttpClient): HttpClient {
    val engine = OkHttp.create {
        preconfigured = okHttpClient
    }
    return createImageHttpClient(engine)
}

fun createAndroidGithubHttpClient(okHttpClient: OkHttpClient): HttpClient {
    val engine = OkHttp.create {
        preconfigured = okHttpClient
    }
    return createGithubHttpClient(engine)
}
