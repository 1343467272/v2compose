package io.github.v2compose.singbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.UnknownHostException

private const val TAG = "SingBoxNetwork"

private const val RCODE_NXDOMAIN = 3

/**
 * 把 Android 的默认网络变化转换成 libbox 需要的 [InterfaceUpdateListener] 回调。
 * sing-box 需要知道当前默认出口网卡（用于 outbound 绑定、network_path 路由规则等）。
 */
class SingBoxNetworkMonitor(context: Context) {

    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var defaultNetwork: Network? = null
        private set

    private var listener: InterfaceUpdateListener? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(target: InterfaceUpdateListener) {
        stop()
        listener = target
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                update(network)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                update(network)
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    listener?.updateDefaultInterface("", 0, false, false)
                }
            }
        }
        callback = networkCallback
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "register default network callback failed", it) }
        connectivity.activeNetwork?.let { update(it) }
    }

    fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        listener = null
    }

    private fun update(network: Network) {
        val current = listener ?: return
        val linkProperties = runCatching { connectivity.getLinkProperties(network) }.getOrNull()
            ?: return
        val name = linkProperties.interfaceName ?: return
        defaultNetwork = network
        val capabilities = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: 0 }.getOrDefault(0)
        val expensive = capabilities
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        current.updateDefaultInterface(name, index, expensive, false)
        current.updateNetworkPath(networkPath(capabilities))
    }

    private fun networkPath(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "other"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
}

/**
 * 使用 Android 系统解析器做域名解析。
 *
 * 内嵌核心运行在 App 进程中，不走 VPN，因此直接用 [InetAddress] 走系统 DNS 即可。
 */
class SingBoxLocalResolver : LocalDNSTransport {

    override fun raw(): Boolean = false

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        throw UnsupportedOperationException("sing-box: raw DNS exchange is not supported")
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val addresses = try {
            InetAddress.getAllByName(domain).toList()
        } catch (e: UnknownHostException) {
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }
        val filtered = when {
            network.endsWith("4") -> addresses.filterIsInstance<Inet4Address>()
            network.endsWith("6") -> addresses.filterIsInstance<Inet6Address>()
            else -> addresses
        }
        if (filtered.isEmpty()) {
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }
        ctx.success(filtered.joinToString("\n") { it.hostAddress.orEmpty() })
    }
}
