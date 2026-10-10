package io.github.v2compose.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import io.github.v2compose.datasource.AppPreferences
import io.github.v2compose.network.HttpCacheManager
import io.github.v2compose.network.ProxyManager
import io.github.v2compose.network.SingBoxProxyController
import io.github.v2compose.network.SingBoxState
import io.github.v2compose.network.bean.Release
import io.github.v2compose.repository.AccountRepository
import io.github.v2compose.shared.bean.AppSettings
import io.github.v2compose.shared.bean.DarkMode
import io.github.v2compose.shared.bean.ProxyInfo
import io.github.v2compose.usecase.CheckForUpdatesUseCase
import io.github.v2compose.usecase.RefreshSingBoxSubscriptionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "SettingsViewModel"

class SettingsViewModel(
    private val appPreferences: AppPreferences,
    val checkForUpdates: CheckForUpdatesUseCase,
    private val accountRepository: AccountRepository,
    private val httpCache: HttpCacheManager,
    private val imageDiskCache: DiskCache,
    private val proxyManager: ProxyManager,
    private val singBoxProxyController: SingBoxProxyController,
    private val refreshSingBoxSubscription: RefreshSingBoxSubscriptionUseCase,
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = appPreferences.appSettings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = AppSettings.Default,
        )

    val proxyInfo: StateFlow<ProxyInfo> = appPreferences.proxyInfo
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = ProxyInfo.Default,
        )
    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize = _cacheSize.asStateFlow()

    /** 内嵌 sing-box 核心的运行状态，用于设置页展示 */
    val singBoxState: StateFlow<SingBoxState> = singBoxProxyController.state

    private val _singBoxImportState = MutableStateFlow<SingBoxImportState>(SingBoxImportState.Idle)
    val singBoxImportState: StateFlow<SingBoxImportState> = _singBoxImportState.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = accountRepository.isLoggedIn.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        false,
    )

    init {
        initCacheSize()
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun initCacheSize() {
        viewModelScope.launch {
            val imageCacheSize = imageDiskCache.size.div(1024 * 1024)
            val httpCacheSize = httpCache.size.div(1024 * 1024)
            _cacheSize.emit(imageCacheSize + httpCacheSize)
        }
    }

    fun updateAutoCheckIn(value: Boolean) {
        viewModelScope.launch {
            appPreferences.autoCheckIn(value)
        }
    }

    fun updateReplyWithFloor(value: Boolean) {
        viewModelScope.launch {
            appPreferences.replyWithFloor(value)
        }
    }

    fun setOpenInInternalBrowser(value: Boolean) {
        viewModelScope.launch {
            appPreferences.openInInternalBrowser(value)
        }
    }

    fun setDarkMode(value: DarkMode) {
        viewModelScope.launch {
            appPreferences.darkMode(value)
        }
    }

    fun setTopicTitleTwoLineMax(value: Boolean) {
        viewModelScope.launch {
            appPreferences.topicTitleOverview(value)
        }
    }

    fun toggleHighlightOpReply(value: Boolean) {
        viewModelScope.launch {
            appPreferences.highlightOpReply(value)
        }
    }

    fun changeProxy(proxy: ProxyInfo) {
        viewModelScope.launch {
            appPreferences.proxyInfo(proxy)
            proxyManager.updateProxy(proxy)
            singBoxProxyController.apply(proxy)
        }
    }

    /** 导入（或手动更新）sing-box 订阅链接 */
    fun importSingBoxSubscription(url: String) {
        viewModelScope.launch {
            _singBoxImportState.value = SingBoxImportState.Loading
            when (val result = refreshSingBoxSubscription.import(url)) {
                is RefreshSingBoxSubscriptionUseCase.Result.Success -> {
                    _singBoxImportState.value =
                        SingBoxImportState.Success(result.nodeCount)
                    val proxy = appPreferences.proxyInfo.first()
                    proxyManager.updateProxy(proxy)
                    singBoxProxyController.apply(proxy)
                }

                is RefreshSingBoxSubscriptionUseCase.Result.Failed -> {
                    _singBoxImportState.value =
                        SingBoxImportState.Failed(result.reason, result.message)
                }

                RefreshSingBoxSubscriptionUseCase.Result.Skipped -> {
                    _singBoxImportState.value = SingBoxImportState.Idle
                }
            }
        }
    }


    @OptIn(ExperimentalCoilApi::class)
    fun clearCache() {
        viewModelScope.launch {
            imageDiskCache.clear()
            httpCache.clear()
            _cacheSize.emit(0L)
        }
    }

    fun ignoreRelease(release: Release) {
        viewModelScope.launch {
            appPreferences.ignoredReleaseName(release.tagName)
        }
    }

    suspend fun logout() {
        accountRepository.logout()
    }

}