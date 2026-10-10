package io.github.v2compose.network

import io.github.v2compose.shared.bean.ProxyInfo
import io.github.v2compose.shared.singbox.SingBoxConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS 端的占位实现。
 *
 * sing-box 在 iOS 上需要 NetworkExtension + libbox.xcframework（另外还需要相应的
 * 签名与权限），当前版本尚未支持，这里只把状态置为 [SingBoxState.Unsupported]。
 */
class NoOpSingBoxProxyController : SingBoxProxyController {

    private val _state = MutableStateFlow<SingBoxState>(SingBoxState.Stopped)
    override val state: StateFlow<SingBoxState> = _state.asStateFlow()

    override val localPort: Int? = null

    override fun apply(proxyInfo: ProxyInfo) {
        if (proxyInfo.type == io.github.v2compose.shared.bean.ProxyType.SingBox) {
            _state.value = SingBoxState.Unsupported
        } else {
            _state.value = SingBoxState.Stopped
        }
    }

    override fun stop() {
        _state.value = SingBoxState.Stopped
    }
}
