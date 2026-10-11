package io.github.v2compose.datasource

import androidx.paging.PagingSource
import io.github.v2compose.network.NetworkClientProvider
import io.github.v2compose.network.V2exApi
import io.github.v2compose.network.bean.SoV2EXSearchResultInfo
import io.github.v2compose.network.createV2HttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 搜索结果分页的回归测试。
 *
 * sov2ex 服务端的限制：
 * - size 最大 50，否则 HTTP 400 "too large size"；
 * - from + size 不能超过 1000，否则 HTTP 400 "too deep paging"（而接口 total 常常是几万）。
 * 此前代码会把 total 当成可翻页上限，翻到约第 1000 条后就会持续报错。
 */
class SearchPagingSourceTest {

    @Test
    fun clampsRequestedPageSizeToServiceLimit() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = clientWith(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                jsonResponse(hitsJson(hitCount = 1, total = 200))
            },
        )
        val source = SearchPagingSource("v2ex", V2exApi(FakeNetworkClientProvider(client)))

        try {
            val page = source.load(refresh(loadSize = 100)).asPage()

            assertTrue(requestedUrls.single(), requestedUrls.single().contains("size=50"))
            assertEquals(1, page.data.size)
            assertEquals(50, page.nextKey ?: -1)
            assertNull(page.prevKey)
        } finally {
            client.close()
        }
    }

    @Test
    fun doesNotRequestBeyondServiceResultWindow() = runBlocking {
        var requests = 0
        val client = clientWith(
            MockEngine {
                requests++
                jsonResponse(hitsJson(hitCount = 1, total = 100_000))
            },
        )
        val source = SearchPagingSource("v2ex", V2exApi(FakeNetworkClientProvider(client)))

        try {
            val page = source.load(refresh(from = 1000, loadSize = 10)).asPage()

            assertEquals("超出翻页窗口时不应再发请求", 0, requests)
            assertTrue(page.data.isEmpty())
            assertNull(page.prevKey)
            assertNull(page.nextKey)
        } finally {
            client.close()
        }
    }

    @Test
    fun stopsPagingWhenNextPageWouldExceedWindow() = runBlocking {
        val client = clientWith(
            MockEngine { jsonResponse(hitsJson(hitCount = 1, total = 100_000)) },
        )
        val source = SearchPagingSource("v2ex", V2exApi(FakeNetworkClientProvider(client)))

        try {
            val page = source.load(refresh(from = 990, loadSize = 10)).asPage()

            assertEquals(1, page.data.size)
            assertNull("不能给出必然触发 HTTP 400 的 nextKey", page.nextKey)
        } finally {
            client.close()
        }
    }

    @Test
    fun keepsPagingWhileInsideWindow() = runBlocking {
        val client = clientWith(
            MockEngine { jsonResponse(hitsJson(hitCount = 1, total = 100_000)) },
        )
        val source = SearchPagingSource("v2ex", V2exApi(FakeNetworkClientProvider(client)))

        try {
            val page = source.load(refresh(from = 30, loadSize = 10)).asPage()

            assertEquals(40, page.nextKey ?: -1)
        } finally {
            client.close()
        }
    }

    @Test
    fun returnsErrorInsteadOfCrashingOnServiceFailure() = runBlocking {
        val client = clientWith(
            MockEngine {
                respond(
                    """{"message":"too deep paging"}""",
                    HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val source = SearchPagingSource("v2ex", V2exApi(FakeNetworkClientProvider(client)))

        try {
            val result = source.load(refresh(loadSize = 10))

            assertTrue("expected LoadResult.Error but was " + result, result is PagingSource.LoadResult.Error)
        } finally {
            client.close()
        }
    }

    @Test
    fun propagatesCancellation() = runBlocking {
        val client = clientWith(
            MockEngine { throw CancellationException("cancelled") },
        )
        val source = SearchPagingSource("v2ex", V2exApi(FakeNetworkClientProvider(client)))

        try {
            source.load(refresh(loadSize = 10))
            fail("CancellationException 必须向上抛出，否则 Paging 会把取消当成加载失败")
        } catch (e: CancellationException) {
            // expected
        } finally {
            client.close()
        }
    }

    private fun clientWith(engine: MockEngine): HttpClient =
        createV2HttpClient(engine = engine)

    private fun refresh(from: Int? = null, loadSize: Int): PagingSource.LoadParams<Int> =
        PagingSource.LoadParams.Refresh(key = from, loadSize = loadSize, placeholdersEnabled = false)

    @Suppress("UNCHECKED_CAST")
    private fun PagingSource.LoadResult<Int, SoV2EXSearchResultInfo.Hit>.asPage():
        PagingSource.LoadResult.Page<Int, SoV2EXSearchResultInfo.Hit> {
        assertTrue("expected Page but was " + this, this is PagingSource.LoadResult.Page)
        return this as PagingSource.LoadResult.Page<Int, SoV2EXSearchResultInfo.Hit>
    }

    private fun MockRequestHandleScope.jsonResponse(body: String) = respond(
        body,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun hitsJson(hitCount: Int, total: Int): String {
        val hits = (1..hitCount).joinToString(",") { index ->
            """{"_source":{"id":""" + index + ""","title":"t""" + index + """","content":"c","node":1,"replies":0,"created":"2020-01-01T00:00:00","member":"u"}}"""
        }
        return """{"total":""" + total + ""","hits":[""" + hits + "]}"
    }

    private class FakeNetworkClientProvider(private val client: HttpClient) : NetworkClientProvider {
        override fun v2HttpClient(): HttpClient = client

        override fun imageHttpClient(): HttpClient = client

        override fun githubHttpClient(): HttpClient = client
    }
}
