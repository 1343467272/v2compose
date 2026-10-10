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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "SingBoxNetwork"

private const val RCODE_NXDOMAIN = 3

private const val RCODE_NOT_IMPLEMENTED = 4

private const val RCODE_SERVFAIL = 2

/**
 * 把 Android 的默认网络变化转换成 libbox 需要的 [InterfaceUpdateListener] 回调。
 *
 * 注意：ConnectivityManager 回调线程是 Java 创建的线程，直接回调进 Go（gobind）在部分
 * Android 版本上会因线程栈问题崩溃（golang/go#68760），因此这里统一切到独立线程再回调。
 */
class SingBoxNetworkMonitor(context: Context) {

    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var defaultNetwork: Network? = null
        private set

    private var listener: InterfaceUpdateListener? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var callbackExecutor: ExecutorService? = null

    fun start(target: InterfaceUpdateListener) {
        stop()
        listener = target
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sing-box-network")
        }
        callbackExecutor = executor
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                executor.execute { update(network) }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                executor.execute { update(network) }
            }

            override fun onLost(network: Network) {
                executor.execute { onNetworkLost(network) }
            }
        }
        callback = networkCallback
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "register default network callback failed", it) }
        executor.execute {
            runCatching { connectivity.activeNetwork?.let { update(it) } }
                .onFailure { Log.w(TAG, "sync default network failed", it) }
        }
    }

    fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        listener = null
        callbackExecutor?.shutdown()
        callbackExecutor = null
    }

    private fun onNetworkLost(network: Network) {
        if (defaultNetwork == network) {
            defaultNetwork = null
            runCatching { listener?.updateDefaultInterface("", 0, false, false) }
                .onFailure { Log.w(TAG, "notify default interface lost failed", it) }
        }
    }

    private fun update(network: Network) {
        val current = listener ?: return
        SingBoxStepLogger.step("platform: notify default interface")
        val linkProperties = runCatching { connectivity.getLinkProperties(network) }.getOrNull()
            ?: return
        val name = linkProperties.interfaceName ?: return
        defaultNetwork = network
        val capabilities = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: 0 }.getOrDefault(0)
        val expensive = capabilities
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        runCatching { current.updateDefaultInterface(name, index, expensive, false) }
            .onFailure { Log.w(TAG, "notify default interface failed", it) }
        runCatching { current.updateNetworkPath(networkPath(capabilities)) }
            .onFailure { Log.w(TAG, "notify network path failed", it) }
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
 * 这里绝不向 Go 抛异常：回调跨 JNI 抛出会变成 Go 侧错误甚至 panic。
 */
class SingBoxLocalResolver : LocalDNSTransport {

    override fun raw(): Boolean = false

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        // raw() == false 时不会走到这里；仍然给出 DNS 错误码而不是抛异常
        runCatching { ctx.errorCode(RCODE_NOT_IMPLEMENTED) }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        try {
            SingBoxStepLogger.step("platform: dns lookup " + domain)
            val addresses = InetAddress.getAllByName(domain).toList()
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
        } catch (e: UnknownHostException) {
            runCatching { ctx.errorCode(RCODE_NXDOMAIN) }
        } catch (t: Throwable) {
            Log.w(TAG, "dns lookup failed: " + domain, t)
            runCatching { ctx.errorCode(RCODE_SERVFAIL) }
        }
    }
}
