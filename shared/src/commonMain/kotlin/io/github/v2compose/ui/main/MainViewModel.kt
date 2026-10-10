package io.github.v2compose.ui.main

import androidx.compose.runtime.MutableIntState
import io.github.v2compose.PlatformCapabilities
import androidx.lifecycle.viewModelScope
import io.github.v2compose.datasource.AppPreferences
import io.github.v2compose.network.SingBoxProxyController
import io.github.v2compose.network.bean.Release
import io.github.v2compose.repository.AccountRepository
import io.github.v2compose.ui.BaseViewModel
import io.github.v2compose.usecase.CheckForUpdatesUseCase
import io.github.v2compose.usecase.CheckInUseCase
import io.github.v2compose.usecase.LoadNodesUseCase
import io.github.v2compose.usecase.RefreshSingBoxSubscriptionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import v2compose.shared.generated.resources.Res
import v2compose.shared.generated.resources.daily_mission_failure

class MainViewModel(
    private val checkForUpdates: CheckForUpdatesUseCase,
    private val checkIn: CheckInUseCase,
    private val appPreferences: AppPreferences,
    private val accountRepository: AccountRepository,
    private val platformCapabilities: PlatformCapabilities,
    private val autoCheckInScheduler: AutoCheckInScheduler,
    private val webViewProxyController: WebViewProxyController,
    private val singBoxProxyController: SingBoxProxyController,
    private val refreshSingBoxSubscription: RefreshSingBoxSubscriptionUseCase,
    val loadNodes: LoadNodesUseCase,
) : BaseViewModel() {

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex = _selectedTabIndex.asStateFlow()

    private val _newRelease = MutableStateFlow(Release.Empty)
    val newRelease = _newRelease.asStateFlow()

    val unreadNotifications = accountRepository.unreadNotifications
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = 0,
        )

    init {
        autoCheckForUpdates()
        listenCanCheckIn()
        listenAutoCheckIn()
        initProxy()
        autoRefreshSingBoxSubscription()
    }

    /** 订阅过期时（默认 6 小时）自动更新一次 */
    private fun autoRefreshSingBoxSubscription() {
        viewModelScope.launch {
            refreshSingBoxSubscription.refreshIfNeeded()
        }
    }

    private fun autoCheckForUpdates() {
        viewModelScope.launch {
            _newRelease.emit(checkForUpdates())
        }
    }

    private fun listenCanCheckIn() {
        if (!platformCapabilities.supportsAutoCheckIn) return
        viewModelScope.launch {
            accountRepository.hasCheckingInTips
                .combine(accountRepository.autoCheckIn) { hasCheckingInTips, autoCheckIn ->
                    hasCheckingInTips && autoCheckIn
                }
                .distinctUntilChanged()
                .collectLatest { shouldCheckIn ->
                    if (shouldCheckIn) {
                        checkInInternal()
                    }
                }
        }
    }

    private suspend fun checkInInternal() {
        val result = checkIn()
        if (result.success) {
            result.message?.let { message ->
                updateSnackbarMessage(message)
            }
        } else {
            updateSnackbarMessage(result.message ?: getString(Res.string.daily_mission_failure))
        }
    }

    private fun listenAutoCheckIn() {
        if (!platformCapabilities.supportsAutoCheckIn) return
        viewModelScope.launch {
            accountRepository.isLoggedIn
                .combine(accountRepository.autoCheckIn) { isLoggedIn, autoCheckIn ->
                    isLoggedIn && autoCheckIn
                }
                .distinctUntilChanged()
                .collectLatest { shouldCheckIn ->
                    if (shouldCheckIn) {
                        checkInInternal()
                    }
                    autoCheckInScheduler.syncAutoCheckIn(shouldCheckIn)
                }
        }
    }

    private fun initProxy() {
        // 代理配置变化时同步内嵌 sing-box 核心的启停
        viewModelScope.launch {
            appPreferences.proxyInfo.collectLatest { proxyInfo ->
                singBoxProxyController.apply(proxyInfo)
            }
        }
        // 内嵌核心启动/停止后端口才可用，因此同时观察核心状态再更新 WebView 代理
        viewModelScope.launch {
            appPreferences.proxyInfo
                .combine(singBoxProxyController.state) { proxyInfo, _ -> proxyInfo }
                .collectLatest { proxyInfo ->
                    webViewProxyController.updateWebViewProxy(proxyInfo)
                }
        }
    }

    fun resetNewRelease() {
        viewModelScope.launch {
            _newRelease.emit(Release.Empty)
        }
    }

    fun ignoreRelease(release: Release) {
        viewModelScope.launch {
            appPreferences.ignoredReleaseName(release.tagName)
        }
    }

    fun loadNodes() {
        viewModelScope.launch {
            loadNodes.execute()
        }
    }

    fun updateSelectedTabIndex(index: Int) {
        viewModelScope.launch {
            _selectedTabIndex.emit(index)
        }
    }
}
