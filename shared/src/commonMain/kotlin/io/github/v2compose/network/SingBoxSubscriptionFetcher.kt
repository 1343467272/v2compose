package io.github.v2compose.network

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** 拉取 sing-box 订阅内容（可能是完整配置，也可能是分享链接列表） */
interface SingBoxSubscriptionFetcher {
    suspend fun fetch(url: String): String
}

class DefaultSingBoxSubscriptionFetcher(
    private val networkClientProvider: NetworkClientProvider,
) : SingBoxSubscriptionFetcher {

    override suspend fun fetch(url: String): String {
        val client = networkClientProvider.githubHttpClient()
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            error("HTTP " + response.status.value)
        }
        return response.bodyAsText()
    }
}
