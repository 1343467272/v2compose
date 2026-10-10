package io.github.v2compose.network.di

import android.text.TextUtils
import io.github.v2compose.datasource.AppPreferences
import io.github.v2compose.network.ProxyManager
import io.github.v2compose.network.SingBoxProxyController
import io.github.v2compose.shared.bean.ProxyInfo
import io.github.v2compose.shared.bean.ProxyType
import io.github.v2compose.shared.singbox.SingBoxConfig
import io.github.v2compose.util.InetValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class V2ProxySelector(
    appPreferences: AppPreferences,
    private val singBoxProxyController: SingBoxProxyController,
) : ProxySelector(), ProxyManager {

    private lateinit var proxyInfo: ProxyInfo
    private lateinit var delegation: ProxySelector

    init {
        val localProxyInfo = runBlocking { appPreferences.proxyInfo.first() }
        updateProxy(localProxyInfo)
    }

    override fun updateProxy(proxyInfo: ProxyInfo) {
        this.proxyInfo = proxyInfo
        delegation = when (proxyInfo.type) {
            ProxyType.System -> getDefault() ?: NullProxySelector()
            else -> NullProxySelector()
        }
    }

    override fun select(uri: URI): List<Proxy> {
        val proxy = when (proxyInfo.type) {
            ProxyType.Http -> manualProxy(Proxy.Type.HTTP)
            ProxyType.Socks -> manualProxy(Proxy.Type.SOCKS)
            ProxyType.SingBox -> singBoxProxy()
            else -> null
        }
        return proxy?.let { listOf(it) } ?: delegation.select(uri)
    }

    private fun manualProxy(type: Proxy.Type): Proxy? {
        return try {
            val ip: String = proxyInfo.address
            val port: Int = proxyInfo.port
            if (!TextUtils.isEmpty(ip) && InetValidator.isValidInetPort(port)) {
                val inetAddress = InetAddress.getByName(ip)
                val socketAddress: SocketAddress = InetSocketAddress(inetAddress, port)
                Proxy(type, socketAddress)
            } else {
                null
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 内嵌 sing-box 的本地 mixed 入站同时支持 HTTP 与 SOCKS5，
     * 这里按 HTTP 代理交给 OkHttp。
     *
     * 核心未处于运行状态时返回 null，回退到 [delegation]（直连），
     * 避免因为代理端口不存在导致整个 App 无法联网。
     */
    private fun singBoxProxy(): Proxy? {
        val port = singBoxProxyController.localPort ?: return null
        return try {
            Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress(InetAddress.getByName(SingBoxConfig.LOCAL_ADDRESS), port),
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        ioe?.printStackTrace()
    }

}

class NullProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        requireNotNull(uri) { "uri must not be null" }
        return listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
    }
}