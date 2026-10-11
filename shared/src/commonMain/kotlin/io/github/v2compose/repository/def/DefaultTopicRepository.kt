package io.github.v2compose.repository.def

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.v2compose.V2exUri
import io.github.v2compose.datasource.AccountPreferences
import io.github.v2compose.datasource.AppPreferences
import io.github.v2compose.datasource.SearchPagingSource
import io.github.v2compose.datasource.TopicPagingSource
import io.github.v2compose.network.V2exApi
import io.github.v2compose.network.bean.AppendTopicPageInfo
import io.github.v2compose.network.bean.CreateTopicPageInfo
import io.github.v2compose.network.bean.ReplyTopicResultInfo
import io.github.v2compose.network.bean.SoV2EXSearchResultInfo
import io.github.v2compose.network.bean.TopicInfo
import io.github.v2compose.network.bean.V2exResult
import io.github.v2compose.repository.ActionMethod
import io.github.v2compose.repository.TopicRepository
import io.github.v2compose.shared.bean.ContentFormat
import io.github.v2compose.shared.bean.DraftTopic
import io.github.v2compose.shared.bean.TopicNode
import io.github.v2compose.util.KLogger
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "DefaultTopic"

class DefaultTopicRepository(
    private val v2exService: V2exApi,
    private val appPreferences: AppPreferences,
    private val accountPreferences: AccountPreferences,
) : TopicRepository {

    override suspend fun getTopicInfo(topicId: String): TopicInfo {
        return v2exService.topicDetails(topicId, 1)
    }

    override fun getTopic(
        topicId: String,
        initialPage: Int?,
        reversed: Boolean,
    ): Flow<PagingData<Any>> {
        KLogger.d(
            TAG,
            "getTopic, topicId = $topicId, initialPage = $initialPage, reversed = $reversed"
        )
        return Pager(PagingConfig(pageSize = 10), initialKey = initialPage) {
            TopicPagingSource(
                topicId = topicId,
                initialPage = initialPage,
                reversed = reversed,
                v2exService = v2exService,
            )
        }.flow
    }

    override val repliesOrderReversed: Flow<Boolean>
        get() = appPreferences.appSettings.map { it.topicRepliesReversed }

    override suspend fun toggleRepliesReversed() {
        appPreferences.toggleTopicRepliesOrder()
    }

    override val highlightOpReply: Flow<Boolean>
        get() = appPreferences.appSettings.map { it.highlightOpReply }

    override val replyWithFloor: Flow<Boolean>
        get() = appPreferences.appSettings.map { it.replyWithFloor }

    override fun search(keyword: String): Flow<PagingData<SoV2EXSearchResultInfo.Hit>> {
        // sov2ex 单次请求的 size 越大响应越慢（实测同一关键词 size=30 约为 size=10 的 2 倍），
        // 因此首屏只请求一页，后续由 Paging 预取，减少等待时间。
        return Pager(PagingConfig(pageSize = 10, initialLoadSize = 10)) {
            SearchPagingSource(keyword, v2exService)
        }.flow
    }

    override val topicTitleOverview: Flow<Boolean>
        get() = appPreferences.appSettings.map { it.topicTitleOverview }

    override suspend fun doTopicAction(
        action: String,
        method: ActionMethod,
        topicId: String,
        once: String,
    ): V2exResult {
        val topicUrl = V2exUri.topicUrl(topicId)
        return when (method) {
            ActionMethod.Get -> v2exService.getTopicAction(topicUrl, action, topicId, once)
            ActionMethod.Post -> v2exService.postTopicAction(topicUrl, action, topicId, once)
        }
    }

    override suspend fun doReplyAction(
        action: String,
        method: ActionMethod,
        topicId: String,
        replyId: String,
        once: String
    ): V2exResult {
        val topicUrl = V2exUri.topicUrl(topicId)
        return when (method) {
            ActionMethod.Get -> v2exService.getReplyAction(topicUrl, action, replyId, once)
            ActionMethod.Post -> v2exService.postReplyAction(topicUrl, action, replyId, once)
        }
    }

    override suspend fun ignoreReply(
        topicId: String,
        replyId: String,
        once: String
    ): Boolean {
        val topicUrl = V2exUri.topicUrl(topicId)
        return v2exService.ignoreReply(topicUrl, replyId, once).status.isSuccess()
    }

    override suspend fun replyTopic(
        topicId: String,
        content: String,
        once: String
    ): ReplyTopicResultInfo {
        val params = mapOf("content" to content, "once" to once)
        return v2exService.replyTopic(topicId, params)
    }

    override val draftTopic: Flow<DraftTopic>
        get() = accountPreferences.draftTopic

    override suspend fun saveDraftTopic(
        title: String,
        content: String,
        contentFormat: ContentFormat,
        node: TopicNode?
    ) {
        accountPreferences.draftTopic(DraftTopic(title, content, contentFormat, node))
    }

    override suspend fun getCreateTopicPageInfo(): CreateTopicPageInfo {
        return v2exService.createTopicPageInfo()
    }

    override suspend fun getTopicNodes(): List<TopicNode> {
        return v2exService.topicNodes()
    }

    override suspend fun createTopic(
        title: String,
        content: String,
        contentFormat: ContentFormat,
        nodeName: String,
        once: String
    ): CreateTopicPageInfo {
        //syntax, default:v2ex原生格式，markdown:Markdown格式
        val syntax = when (contentFormat) {
            ContentFormat.Original -> "default"
            ContentFormat.Markdown -> "markdown"
        }
        val params =
            mapOf(
                "title" to title,
                "content" to content,
                "node_name" to nodeName,
                "once" to once,
                "syntax" to syntax,
            )
        return v2exService.createTopic(params)
    }

    override suspend fun getAppendTopicPageInfo(topicId: String): AppendTopicPageInfo {
        val topicUrl = V2exUri.topicUrl(topicId)
        return v2exService.appendTopicPageInfo(topicUrl, topicId)
    }

    override suspend fun addSupplement(
        topicId: String,
        supplement: String,
        contentFormat: ContentFormat,
        once: String
    ): AppendTopicPageInfo {
        //syntax, 0: default, 1:markdown
        val syntax = when (contentFormat) {
            ContentFormat.Original -> 0
            ContentFormat.Markdown -> 1
        }
        val params = mapOf("once" to once, "content" to supplement, "syntax" to syntax.toString())
        return v2exService.appendTopic(topicId, params)
    }
}