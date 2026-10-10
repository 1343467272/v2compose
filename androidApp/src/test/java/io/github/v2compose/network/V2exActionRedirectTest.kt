package io.github.v2compose.network

import com.sun.net.httpserver.HttpServer
import io.github.fruit.Fruit
import io.github.v2compose.core.extension.isRedirect
import io.github.v2compose.core.extension.redirectLocation
import io.github.v2compose.network.bean.V2exResult
import io.ktor.client.call.body
import io.ktor.client.request.get
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
 * V2EX 的业务动作接口（感谢/收藏/忽略等）成功时返回 302 重定向，
 * 客户端必须让该 3xx 直接暴露为 [io.ktor.client.plugins.ResponseException]，
 * 交给上层 isRedirect 逻辑处理；一旦被跟随到目标 HTML 页面，
 * ContentNegotiation 会尝试用 Fruit 把页面解析成 V2exResult 并抛出
 * "Fruit cannot handle class ... V2exResult. No factory found."。
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
            if (path == "/thank/reply/1") {
                exchange.responseHeaders.add("Location", "/t/1")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                val html = "<html><body>topic page</body></html>".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, html.size.toLong())
                exchange.responseBody.use { it.write(html) }
            }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun businessActionRedirectIsNotFollowedOrParsedAsHtml() = runBlocking {
        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
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
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
        }
    }
}
