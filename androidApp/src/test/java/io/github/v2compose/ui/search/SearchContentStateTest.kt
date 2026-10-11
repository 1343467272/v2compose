package io.github.v2compose.ui.search

import androidx.paging.LoadState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索结果页内容状态的回归测试。
 *
 * 修复前：页面只看 itemSnapshotList 是否为空，于是"加载中""加载失败""没有匹配结果"
 * 三种状态都会被当成空列表并回退到历史搜索词——用户看到的就是"搜索不出结果"，
 * 而且失败后连重试入口都没有。
 */
class SearchContentStateTest {

    private val notLoading = LoadState.NotLoading(endOfPaginationReached = true)
    private val loading = LoadState.Loading
    private val error = LoadState.Error(RuntimeException("boom"))

    @Test
    fun showsHistoryBeforeAnySearch() {
        assertEquals(SearchContentState.History, searchContentState(null, notLoading, 0))
        assertEquals(SearchContentState.History, searchContentState("", loading, 0))
    }

    @Test
    fun showsLoadingWhileFirstPageIsLoading() {
        assertEquals(SearchContentState.Loading, searchContentState("流量卡", loading, 0))
    }

    @Test
    fun showsErrorWhenFirstPageFailed() {
        assertEquals(SearchContentState.Error, searchContentState("流量卡", error, 0))
    }

    @Test
    fun showsEmptyStateWhenQueryHasNoHit() {
        assertEquals(SearchContentState.Empty, searchContentState("流量卡", notLoading, 0))
    }

    @Test
    fun showsResultsWhenItemsAreLoaded() {
        assertEquals(SearchContentState.Results, searchContentState("流量卡", notLoading, 10))
        // 已有数据时即使刷新失败也应保留列表，由 pagingRefreshItem 展示错误。
        assertEquals(SearchContentState.Results, searchContentState("流量卡", error, 10))
    }
}
