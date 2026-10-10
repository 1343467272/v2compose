package io.github.v2compose.datasource

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.v2compose.shared.bean.AppSettings
import io.github.v2compose.shared.bean.DarkMode
import io.github.v2compose.shared.bean.ProxyInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val TAG = "AppPreferences"

class AppPreferences(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        private val KeyTopicRepliesReversed = booleanPreferencesKey("topic_replies_reversed")
        private val KeyOpenInInternalBrowser = booleanPreferencesKey("open_in_internal_browser")
        private val KeyDarkMode = stringPreferencesKey("dark_mode")
        private val KeyTopicTitleOverview = booleanPreferencesKey("topic_title_overview")
        private val KeyIgnoredReleaseName = stringPreferencesKey("ignored_release_name")
        private val KeyAutoCheckIn = booleanPreferencesKey("auto_check_in")
        private val KeySearchKeywords = stringPreferencesKey("search_keywords")
        private val KeyHighlightOpReply = booleanPreferencesKey("highlight_op_reply")
        private val KeyReplyWithFloor = booleanPreferencesKey("reply_with_floor")

        private val KeyProxyInfo = stringPreferencesKey("proxy_info")

        /** sing-box 核心连续启动次数，用于在核心导致进程崩溃后自动停止重试 */
        private val KeySingBoxStartAttempts = intPreferencesKey("singbox_start_attempts")
    }

    val appSettings: Flow<AppSettings> = dataStore.data.map {
        AppSettings(
            topicRepliesReversed = it[KeyTopicRepliesReversed] ?: true,
            openInInternalBrowser = it[KeyOpenInInternalBrowser] ?: true,
            darkMode = it[KeyDarkMode]?.let { value -> DarkMode.valueOf(value) }
                ?: DarkMode.FollowSystem,
            topicTitleOverview = it[KeyTopicTitleOverview] ?: true,
            ignoredReleaseName = it[KeyIgnoredReleaseName],
            autoCheckIn = it[KeyAutoCheckIn] ?: false,
            searchKeywords = it[KeySearchKeywords]?.split(",") ?: listOf(),
            highlightOpReply = it[KeyHighlightOpReply] ?: false,
            replyWithFloor = it[KeyReplyWithFloor] ?: true,
        )
    }.distinctUntilChanged()

    val proxyInfo: Flow<ProxyInfo> = dataStore.data.map {
        it[KeyProxyInfo]?.let { value -> ProxyInfo.fromJson(value) } ?: ProxyInfo.Default
    }

    suspend fun toggleTopicRepliesOrder() {
        dataStore.edit {
            it[KeyTopicRepliesReversed] = !(it[KeyTopicRepliesReversed] ?: true)
        }
    }

    suspend fun openInInternalBrowser(value: Boolean) {
        dataStore.edit {
            it[KeyOpenInInternalBrowser] = value
        }
    }

    suspend fun darkMode(value: DarkMode) {
        dataStore.edit {
            it[KeyDarkMode] = value.name
        }
    }

    suspend fun topicTitleOverview(value: Boolean) {
        dataStore.edit {
            it[KeyTopicTitleOverview] = value
        }
    }

    suspend fun ignoredReleaseName(value: String) {
        dataStore.edit {
            it[KeyIgnoredReleaseName] = value
        }
    }

    suspend fun autoCheckIn(value: Boolean) {
        dataStore.edit {
            it[KeyAutoCheckIn] = value
        }
    }

    suspend fun replyWithFloor(value: Boolean) {
        dataStore.edit {
            it[KeyReplyWithFloor] = value
        }
    }

    suspend fun searchKeywords(value: List<String>) {
        dataStore.edit {
            it[KeySearchKeywords] = value.joinToString(",")
        }
    }

    suspend fun highlightOpReply(value: Boolean) {
        dataStore.edit {
            it[KeyHighlightOpReply] = value
        }
    }

    suspend fun proxyInfo(proxy: ProxyInfo) {
        dataStore.edit {
            it[KeyProxyInfo] = proxy.toJson()
        }
    }

    /**
     * sing-box 核心的「未确认启动」次数：启动前 +1，启动成功或捕获到异常后清零。
     * 只有进程被核心带崩时该值才会残留，下次启动据此跳过自动启动，避免反复闪退。
     */
    val singBoxStartAttempts: Flow<Int> = dataStore.data.map {
        it[KeySingBoxStartAttempts] ?: 0
    }

    suspend fun singBoxStartAttempts(value: Int) {
        dataStore.edit {
            it[KeySingBoxStartAttempts] = value
        }
    }
}
