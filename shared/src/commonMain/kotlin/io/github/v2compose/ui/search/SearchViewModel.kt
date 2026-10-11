package io.github.v2compose.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import io.github.v2compose.core.StringDecoder
import io.github.v2compose.datasource.AppPreferences
import io.github.v2compose.repository.TopicRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(
    savedStateHandle: SavedStateHandle,
    stringDecoder: StringDecoder,
    private val topicRepository: TopicRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val searchArgs = SearchArgs(savedStateHandle, stringDecoder)

    private val _keyword = MutableStateFlow(searchArgs.keyword)
    val keyword: StateFlow<String?> = _keyword.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyKeywords = appPreferences.appSettings.mapLatest { it.searchKeywords }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), listOf())

    /**
     * 搜索请求流。这里不用 keyword(StateFlow) 直接驱动请求：
     * StateFlow 会丢弃与当前值相等的值，导致"再次搜索同一个关键词"不会重新发起请求
     * （上次失败后重试、下拉刷新等场景都会失效）。SharedFlow 每次都会重新触发。
     */
    private val searchRequests = MutableSharedFlow<String>(replay = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    val topics = searchRequests
        .flatMapLatest { topicRepository.search(it) }
        .cachedIn(viewModelScope)

    init {
        searchArgs.keyword?.takeIf { it.isNotEmpty() }?.let(searchRequests::tryEmit)
    }

    fun search(value: String) {
        viewModelScope.launch {
            _keyword.value = value
            searchRequests.emit(value)
            val searchKeywords = historyKeywords.value.toMutableList()
                .also {
                    it.remove(value)
                    it.add(0, value)
                }
            appPreferences.searchKeywords(searchKeywords.take(10))
        }
    }

    fun clearHistoryKeywords() {
        viewModelScope.launch {
            appPreferences.searchKeywords(listOf())
        }
    }
}
