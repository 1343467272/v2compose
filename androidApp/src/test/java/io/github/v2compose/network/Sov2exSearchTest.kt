package io.github.v2compose.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * sov2ex（第三方域名）搜索接口的回归测试。
 *
 * 背景：V2 客户端为了把 V2EX 的业务 302 暴露给上层而关闭了自动重定向跟随（followRedirects = false），
 * 但搜索请求打到的是 www.sov2ex.com，一旦该域名返回 3xx，搜索就会直接失败。
 * 这里覆盖：参数拼接、相对/绝对重定向跟随、重定向次数上限、非 2xx 的可读错误。
 */
class Sov2exSearchTest {

    @Test
    fun searchSendsKeywordAndPagingParams() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = createV2HttpClient(
            engine = MockEngine { request ->
                requestedUrls += request.url.toString()
                respondJson(HITS_JSON)
            },
        )
        val api = V2exApi(FakeNetworkClientProvider(client))

        try {
            val result = api.search(keyword = "流量卡", from = 30, size = 10)

            assertEquals(2, result.total)
            assertEquals(7, result.hits.first().source.id)
            assertEquals("流量如何共享给其他 SIM 卡？", result.hits.first().source.title)

            val url = requestedUrls.single()
            assertTrue(url, url.startsWith("https://www.sov2ex.com/api/search?"))
            assertTrue(url, url.contains("q="))
            assertTrue(url, url.contains("from=30"))
            assertTrue(url, url.contains("size=10"))
        } finally {
            client.close()
        }
    }

    @Test
    fun v2HttpClientInstallsTimeoutPlugin() {
        // 搜索依赖每请求 timeout { } 生效，插件必须已安装，否则超时会被静默忽略。
        val client = createV2HttpClient(engine = MockEngine { respondJson(HITS_JSON) })
        try {
            assertTrue(client.pluginOrNull(HttpTimeout) != null)
        } finally {
            client.close()
        }
    }

    @Test
    fun searchFollowsRelativeRedirect() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = createV2HttpClient(
            engine = MockEngine { request ->
                requestedUrls += request.url.toString()
                if (requestedUrls.size == 1) {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/api/search?v=2"))
                } else {
                    respondJson(HITS_JSON)
                }
            },
        )

        try {
            val result = V2exApi(FakeNetworkClientProvider(client)).search("流量卡", 0, 10)

            assertEquals(2, result.total)
            assertEquals(2, requestedUrls.size)
            assertEquals("https://www.sov2ex.com/api/search?v=2", requestedUrls[1])
        } finally {
            client.close()
        }
    }

    @Test
    fun searchFollowsAbsoluteRedirect() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = createV2HttpClient(
            engine = MockEngine { request ->
                requestedUrls += request.url.toString()
                if (requestedUrls.size == 1) {
                    respond(
                        "",
                        HttpStatusCode.MovedPermanently,
                        headersOf(HttpHeaders.Location, "https://mirror.sov2ex.com/api/search?q=x"),
                    )
                } else {
                    respondJson(HITS_JSON)
                }
            },
        )

        try {
            val result = V2exApi(FakeNetworkClientProvider(client)).search("流量卡", 0, 10)

            assertEquals(2, result.total)
            assertEquals("https://mirror.sov2ex.com/api/search?q=x", requestedUrls[1])
        } finally {
            client.close()
        }
    }

    @Test
    fun searchFailsAfterTooManyRedirects() = runBlocking {
        var requests = 0
        val client = createV2HttpClient(
            engine = MockEngine {
                requests++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/api/search?v=2"))
            },
        )

        try {
            V2exApi(FakeNetworkClientProvider(client)).search("流量卡", 0, 10)
            fail("expected a readable search failure")
        } catch (e: Exception) {
            assertEquals("Sov2exSearchException", e::class.java.simpleName)
            assertTrue(e.message ?: "", (e.message ?: "").contains("重定向"))
            assertEquals(4, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun searchThrowsReadableExceptionOnServiceRejection() = runBlocking {
        val client = createV2HttpClient(
            engine = MockEngine {
                respond(
                    """{"message":"too deep paging"}""",
                    HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )

        try {
            V2exApi(FakeNetworkClientProvider(client)).search("流量卡", 1000, 10)
            fail("expected a readable search failure")
        } catch (e: Exception) {
            assertEquals("Sov2exSearchException", e::class.java.simpleName)
            val message = e.message ?: ""
            assertTrue(message, message.contains("400"))
            assertTrue(message, message.contains("too deep paging"))
        } finally {
            client.close()
        }
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        body,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private class FakeNetworkClientProvider(private val client: HttpClient) : NetworkClientProvider {
        override fun v2HttpClient(): HttpClient = client

        override fun imageHttpClient(): HttpClient = client

        override fun githubHttpClient(): HttpClient = client
    }

    private companion object {
        const val HITS_JSON = """{"total":2,"hits":[{"_source":{"id":7,"title":"流量如何共享给其他 SIM 卡？","content":"需求是这样的","node":12,"replies":33,"created":"2019-06-14T08:06:23","member":"Variazioni"}}]}"""
    }
}
