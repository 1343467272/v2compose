package io.github.v2compose.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.v2compose.network.V2exApi
import io.github.v2compose.network.bean.SoV2EXSearchResultInfo
import kotlinx.coroutines.CancellationException

/** sov2ex 单次请求允许的最大条数，超过会返回 HTTP 400（"too large size"）。 */
internal const val SOV2EX_MAX_PAGE_SIZE = 50

/**
 * sov2ex 允许的最大翻页窗口：服务端要求 `from + size <= 1000`，
 * 超出会返回 HTTP 400（"too deep paging"）。而接口返回的 total 常常是几万甚至上百万，
 * 如果不做限制，列表翻到约第 1000 条时就会持续报错。
 */
internal const val SOV2EX_MAX_RESULT_WINDOW = 1000

class SearchPagingSource(private val keyword: String, private val v2exService: V2exApi) :
    PagingSource<Int, SoV2EXSearchResultInfo.Hit>() {

    override fun getRefreshKey(state: PagingState<Int, SoV2EXSearchResultInfo.Hit>): Int? {
        // 搜索始终从第一页开始，刷新时不需要保留位置。
        return null
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SoV2EXSearchResultInfo.Hit> {
        return try {
            val from = params.key ?: 0
            val loadSize = params.loadSize.coerceIn(1, SOV2EX_MAX_PAGE_SIZE)

            // 已经在服务端允许的翻页窗口之外，直接结束分页，避免必然失败的请求。
            if (from + loadSize > SOV2EX_MAX_RESULT_WINDOW) {
                return LoadResult.Page(
                    data = emptyList<SoV2EXSearchResultInfo.Hit>(),
                    prevKey = null,
                    nextKey = null,
                )
            }

            val resultInfo = v2exService.search(keyword = keyword, from = from, size = loadSize)
            val nextFrom = from + loadSize
            val hasMore = nextFrom < resultInfo.total &&
                nextFrom + loadSize <= SOV2EX_MAX_RESULT_WINDOW
            return LoadResult.Page(
                data = resultInfo.hits,
                // 搜索只向后翻页，prevKey 置空可以避免 Paging 触发重复的 preload。
                prevKey = null,
                nextKey = if (hasMore) nextFrom else null,
            )
        } catch (e: CancellationException) {
            // 协程取消必须继续向上抛出，否则会被 Paging 误判为加载失败并卡住 UI。
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            LoadResult.Error(e)
        }
    }
}
