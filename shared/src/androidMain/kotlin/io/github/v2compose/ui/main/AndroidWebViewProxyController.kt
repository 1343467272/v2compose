package io.github.v2compose.ui.main

import io.github.v2compose.network.SingBoxProxyController
import io.github.v2compose.shared.bean.ProxyInfo
import io.github.v2compose.shared.bean.ProxyType
import io.github.v2compose.shared.singbox.SingBoxConfig
import io.github.v2compose.util.WebViewProxy
import java.util.concurrent.ExecutorService

class AndroidWebViewProxyController(
    private val appExecutorService: ExecutorService,
    private val singBoxProxyController: SingBoxProxyController,
) : WebViewProxyController {
    override fun updateWebViewProxy(proxyInfo: ProxyInfo) {
        WebViewProxy.updateProxy(proxyInfo.toEffectiveProxy(), appExecutorService)
    }

    /**
     * sing-box 模式下把 WebView 指向本地 mixed 入站的 HTTP 代理；
     * 核心未运行时回退为直连（清除 WebView 代理），避免网页完全打不开。
     */
    private fun ProxyInfo.toEffectiveProxy(): ProxyInfo {
        if (type != ProxyType.SingBox) return this
        val port = singBoxProxyController.localPort
        return if (port == null) {
            ProxyInfo(ProxyType.Direct)
        } else {
            ProxyInfo(ProxyType.Http, SingBoxConfig.LOCAL_ADDRESS, port)
        }
    }
}
