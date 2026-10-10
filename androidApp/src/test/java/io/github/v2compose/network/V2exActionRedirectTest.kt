package io.github.v2compose.network

import com.sun.net.httpserver.HttpServer
import io.github.fruit.Fruit
import io.github.v2compose.core.extension.isRedirect
import io.github.v2compose.core.extension.redirectLocation
import io.github.v2compose.network.bean.V2exResult
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 重定向跟随策略必须按客户端区分：
 *
 * - V2 客户端：V2EX 的业务动作（感谢/收藏/忽略等）成功时返回 302，必须让该 3xx 直接暴露为
 *   [io.ktor.client.plugins.ResponseException]，交给上层 isRedirect 逻辑处理。一旦被跟随到目标
 *   HTML 页面，ContentNegotiation 会尝试用 Fruit 把页面解析成 V2exResult 并抛出
 *   "Fruit cannot handle class ... V2exResult. No factory found."
 * - 图片客户端：CDN 常见 302（http->https、镜像跳转），必须保持跟随，否则 Coil 加载失败。
 */
class V2exActionRedirectTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private val requestedPaths = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requestedPaths += path
            when (path) {
                "/thank/reply/1" -> exchange.redirect("/t/1")
                "/img/1" -> exchange.redirect("/img/final")
                "/img/final" -> exchange.respondWith(200, "image/png", byteArrayOf(0x1, 0x2, 0x3))
                else -> exchange.respondWith(200, "text/html; charset=utf-8", "<html><body>topic page</body></html>".toByteArray())
            }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    /** 引擎层不跟随重定向，从而单独验证 Ktor 客户端自身的跟随策略。 */
    private fun engineWithoutRedirects() = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Test
    fun businessActionRedirectIsNotFollowedOrParsedAsHtml() = runBlocking {
        val okHttpClient = engineWithoutRedirects()
        val client = createAndroidV2HttpClient(
            okHttpClient = okHttpClient,
            fruit = Fruit.createDefault(),
            eventManager = null,
        )
        try {
            client.get("http://127.0.0.1:$port/thank/reply/1").body<V2exResult>()
            fail("Expected the 302 of a V2EX action to surface as an exception")
        } catch (e: Exception) {
            assertTrue("Expected a 3xx ResponseException but got <$e>", e.isRedirect)
            assertEquals("/t/1", e.redirectLocation)
            assertEquals(listOf("/thank/reply/1"), requestedPaths.toList())
        } finally {
            client.close()
            okHttpClient.dispose()
        }
    }

    @Test
    fun imageClientStillFollowsRedirect() = runBlocking {
        val okHttpClient = engineWithoutRedirects()
        val client = createAndroidImageHttpClient(okHttpClient)
        try {
            val response = client.get("http://127.0.0.1:$port/img/1")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("/img/1", "/img/final"), requestedPaths.toList())
        } finally {
            client.close()
            okHttpClient.dispose()
        }
    }

    private fun com.sun.net.httpserver.HttpExchange.redirect(location: String) {
        responseHeaders.add("Location", location)
        sendResponseHeaders(302, -1)
        close()
    }

    private fun com.sun.net.httpserver.HttpExchange.respondWith(
        status: Int,
        contentType: String,
        body: ByteArray,
    ) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun OkHttpClient.dispose() {
        dispatcher.executorService.shutdown()
        connectionPool.evictAll()
    }
}
