package io.github.v2compose.network

import io.github.v2compose.network.NetConstants.systemUserAgent
import io.github.v2compose.network.bean.AppendTopicPageInfo
import io.github.v2compose.network.bean.CreateTopicPageInfo
import io.github.v2compose.network.bean.DailyInfo
import io.github.v2compose.network.bean.HomePageInfo
import io.github.v2compose.network.bean.LoginParam
import io.github.v2compose.network.bean.MyFollowingInfo
import io.github.v2compose.network.bean.MyNodesInfo
import io.github.v2compose.network.bean.MyTopicsInfo
import io.github.v2compose.network.bean.NewsInfo
import io.github.v2compose.network.bean.Node
import io.github.v2compose.network.bean.NodeInfo
import io.github.v2compose.network.bean.NodeTopicInfo
import io.github.v2compose.network.bean.NodesInfo
import io.github.v2compose.network.bean.NodesNavInfo
import io.github.v2compose.network.bean.NotificationInfo
import io.github.v2compose.network.bean.RecentTopics
import io.github.v2compose.network.bean.ReplyTopicResultInfo
import io.github.v2compose.network.bean.SoV2EXSearchResultInfo
import io.github.v2compose.network.bean.ThxResponseInfo
import io.github.v2compose.network.bean.TopicInfo
import io.github.v2compose.network.bean.TwoStepLoginInfo
import io.github.v2compose.network.bean.UserInfo
import io.github.v2compose.network.bean.UserPageInfo
import io.github.v2compose.network.bean.UserReplies
import io.github.v2compose.network.bean.UserTopics
import io.github.v2compose.network.bean.V2exResult
import io.github.v2compose.shared.bean.TopicNode
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters

/**
 * Multiplatform V2ex Network API using Ktor
 */
class V2exApi(private val clientProvider: NetworkClientProvider) {
    private val client
        get() = clientProvider.v2HttpClient()

    suspend fun nodeInfo(name: String): NodeInfo =
        client.get("/api/nodes/show.json") { parameter("name", name) }.body()

    suspend fun nodes(): NodesInfo = client.get("/api/nodes/s2.json").body()

    suspend fun allNodes(): List<Node> = client.get("/api/nodes/all.json").body()

    suspend fun topicNodes(): List<TopicNode> =
        client.get("/api/nodes/list.json") {
            parameter("fields", "name,title,topics,aliases")
            parameter("sort_by", "topics")
            parameter("reverse", "1")
        }.body()

    suspend fun userInfo(username: String): UserInfo =
        client.get("/api/members/show.json") { parameter("username", username) }.body()

    suspend fun search(keyword: String, from: Int, size: Int): SoV2EXSearchResultInfo =
        client.getFollowingRedirects(buildSov2exSearchUrl(keyword, from, size)).body()

    // Below is html API
    suspend fun homeNews(tab: String): NewsInfo =
        client.get("/") { parameter("tab", tab) }.body()

    suspend fun recentTopics(page: Int): RecentTopics =
        client.get("/recent") { parameter("p", page) }.body()

    suspend fun loginParam(): LoginParam = client.get("/signin").body()

    suspend fun login(loginParams: Map<String, String>): LoginParam =
        client.submitForm(
            url = "/signin",
            formParameters = parameters {
                loginParams.forEach { (k, v) -> append(k, v) }
            }
        ) {
            header(HttpHeaders.Referrer, "https://www.v2ex.com/signin")
        }.body()

    suspend fun topicDetails(topicId: String, page: Int): TopicInfo =
        client.get("/t/$topicId") { parameter("p", page) }.body()

    suspend fun notifications(page: Int): NotificationInfo =
        client.get("/notifications") {
            parameter("p", page)
            header(HttpHeaders.UserAgent, NetConstants.webUserAgent)
        }.body()

    suspend fun myFollowingInfo(page: Int): MyFollowingInfo =
        client.get("/my/following") {
            parameter("p", page)
            header(HttpHeaders.UserAgent, systemUserAgent)
        }.body()

    suspend fun myTopicsInfo(page: Int): MyTopicsInfo =
        client.get("/my/topics") {
            parameter("p", page)
            header(HttpHeaders.UserAgent, systemUserAgent)
        }.body()

    suspend fun myNodesInfo(): MyNodesInfo =
        client.get("/my/nodes") {
            header(HttpHeaders.UserAgent, systemUserAgent)
        }.body()

    suspend fun nodesNavInfo(): NodesNavInfo =
        client.get("/").body()

    suspend fun nodesInfo(node: String, page: Int): NodeTopicInfo =
        client.get("/go/$node") { parameter("p", page) }.body()

    suspend fun homePageInfo(username: String): HomePageInfo =
        client.get("/member/$username").body()

    suspend fun userPageInfo(username: String): UserPageInfo =
        client.get("/member/$username").body()

    suspend fun userTopics(username: String, page: Int): UserTopics =
        client.get("/member/$username/topics") { parameter("p", page) }.body()

    suspend fun userReplies(username: String, page: Int): UserReplies =
        client.get("/member/$username/replies") { parameter("p", page) }.body()

    suspend fun createTopicPageInfo(): CreateTopicPageInfo =
        client.get("/write").body()

    suspend fun createTopic(postParams: Map<String, String>): CreateTopicPageInfo =
        client.submitForm(
            url = "/write",
            formParameters = parameters {
                postParams.forEach { (k, v) -> append(k, v) }
            }
        ).body()

    suspend fun appendTopicPageInfo(referer: String, topicID: String): AppendTopicPageInfo =
        client.get("/append/topic/$topicID") {
            header(HttpHeaders.Referrer, referer)
        }.body()

    suspend fun appendTopic(topicId: String, postParams: Map<String, String>): AppendTopicPageInfo =
        client.submitForm(
            url = "/append/topic/$topicId",
            formParameters = parameters {
                postParams.forEach { (k, v) -> append(k, v) }
            }
        ).body()

    suspend fun thxMoney(): ThxResponseInfo =
        client.post("/ajax/money").body()

    suspend fun getTopicAction(
        referer: String,
        action: String,
        topicId: String,
        once: String
    ): V2exResult =
        client.get("/$action/topic/$topicId") {
            header(HttpHeaders.Referrer, referer)
            parameter("once", once)
        }.body()

    suspend fun postTopicAction(
        referer: String,
        action: String,
        topicId: String,
        once: String
    ): V2exResult =
        client.post("/$action/topic/$topicId") {
            header(HttpHeaders.Referrer, referer)
            parameter("once", once)
        }.body()

    suspend fun getReplyAction(
        referer: String,
        action: String,
        replyId: String,
        once: String
    ): V2exResult =
        client.get("/$action/reply/$replyId") {
            header(HttpHeaders.Referrer, referer)
            parameter("once", once)
        }.body()

    suspend fun postReplyAction(
        referer: String,
        action: String,
        replyId: String,
        once: String
    ): V2exResult =
        client.post("/$action/reply/$replyId") {
            header(HttpHeaders.Referrer, referer)
            parameter("once", once)
        }.body()

    suspend fun ignoreReply(referer: String, replyId: String, once: String): HttpResponse =
        client.post("/ignore/reply/$replyId") {
            header(HttpHeaders.Referrer, referer)
            parameter("once", once)
        }

    suspend fun replyTopic(id: String, replyMap: Map<String, String>): ReplyTopicResultInfo =
        client.submitForm(
            url = "/t/$id",
            formParameters = parameters {
                replyMap.forEach { (k, v) -> append(k, v) }
            }
        ).body()

    suspend fun userAction(referer: String, url: String): UserPageInfo =
        client.get(url) {
            header(HttpHeaders.Referrer, referer)
        }.body()

    suspend fun nodeAction(referer: String, url: String): NodeTopicInfo =
        client.get(url) {
            header(HttpHeaders.Referrer, referer)
        }.body()

    suspend fun dailyInfo(): DailyInfo = client.get("/mission/daily").body()

    suspend fun checkIn(once: String): DailyInfo =
        client.get("/mission/daily/redeem") {
            header(HttpHeaders.Referrer, "https://www.v2ex.com/mission/daily")
            parameter("once", once)
        }.body()

    suspend fun twoStepLogin(): TwoStepLoginInfo = client.get("/2fa").body()

    suspend fun signInTwoStep(map: Map<String, String>): TwoStepLoginInfo =
        client.submitForm(
            url = "/2fa",
            formParameters = parameters {
                map.forEach { (k, v) -> append(k, v) }
            }
        ) {
            header(HttpHeaders.Referrer, "https://www.v2ex.com")
        }.body()
}
