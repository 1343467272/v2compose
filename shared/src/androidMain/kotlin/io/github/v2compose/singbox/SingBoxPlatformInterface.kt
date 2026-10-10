package io.github.v2compose.singbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InterfaceAddress
import java.net.NetworkInterface as JavaNetworkInterface
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * libbox 的 [PlatformInterface] 实现。
 *
 * V2compose 只使用 sing-box 的本地 mixed 入站（App 自身流量走 127.0.0.1），
 * 不使用 TUN / VpnService，因此这里只实现非 VPN 场景真正需要的能力：
 * 默认网卡监听、网卡列表与本地 DNS 解析；其余平台能力（TUN、shell、bridge、
 * 邻居表、通知等）要么是空实现，要么直接抛出不支持。
 */
class SingBoxPlatformInterface(private val context: Context) : PlatformInterface {

    private val networkMonitor = SingBoxNetworkMonitor(context)
    private val localResolver = SingBoxLocalResolver()

    @Volatile
    private var interfacesLogged = false

    // region 网络与 DNS

    override fun localDNSTransport(): LocalDNSTransport? = localResolver

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        SingBoxStepLogger.step("platform: startDefaultInterfaceMonitor")
        networkMonitor.start(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkMonitor.stop()
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        if (!interfacesLogged) {
            interfacesLogged = true
            SingBoxStepLogger.step("platform: getInterfaces() called")
        }
        return runCatching { collectInterfaces() }.getOrElse { throwable ->
            Log.w(TAG, "collect network interfaces failed", throwable)
            SingBoxInterfaceArray(emptyList())
        }
    }

    /** 任何异常都不允许跨 JNI 抛回 Go，返回空列表即可。 */
    @Suppress("DEPRECATION")
    private fun collectInterfaces(): NetworkInterfaceIterator {
        val connectivity = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val result = mutableListOf<LibboxNetworkInterface>()
        for (network in connectivity.allNetworks) {
            val linkProperties = runCatching { connectivity.getLinkProperties(network) }
                .getOrNull() ?: continue
            val capabilities = runCatching { connectivity.getNetworkCapabilities(network) }
                .getOrNull() ?: continue
            val name = linkProperties.interfaceName ?: continue
            val javaInterface = runCatching { JavaNetworkInterface.getByName(name) }
                .getOrNull() ?: continue

            val item = LibboxNetworkInterface()
            item.name = name
            item.index = javaInterface.index
            item.mtu = runCatching { javaInterface.mtu }.getOrDefault(DEFAULT_MTU)
            item.type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    Libbox.InterfaceTypeWIFI

                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    Libbox.InterfaceTypeCellular

                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    Libbox.InterfaceTypeEthernet

                else -> Libbox.InterfaceTypeOther
            }
            item.dnsServer = SingBoxStringArray(
                linkProperties.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }
            )
            item.gateway = SingBoxStringArray(
                linkProperties.routes
                    .filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway?.hostAddress?.substringBefore('%') }
            )
            item.addresses = SingBoxStringArray(
                javaInterface.interfaceAddresses.mapNotNull { it.toPrefix() }
            )
            var flags = 0
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (javaInterface.isLoopback) {
                flags = flags or OsConstants.IFF_LOOPBACK
            }
            if (javaInterface.isPointToPoint) {
                flags = flags or OsConstants.IFF_POINTOPOINT
            }
            if (javaInterface.supportsMulticast()) {
                flags = flags or OsConstants.IFF_MULTICAST
            }
            item.flags = flags
            item.metered =
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result.add(item)
        }
        return SingBoxInterfaceArray(result)
    }

    override fun clearDNSCache() = Unit

    // endregion

    // region 非 VPN 模式下用不到的平台能力

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = false

    override fun autoDetectInterfaceControl(fd: Int) = Unit

    override fun openTun(options: TunOptions): Int {
        SingBoxStepLogger.step("platform: openTun() called (unexpected)")
        throw UnsupportedOperationException(
            "V2compose 内嵌 sing-box 仅支持本地 mixed 入站，请勿在配置中使用 tun 入站"
        )
    }

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        throw UnsupportedOperationException(
            "V2compose 内嵌 sing-box 不支持按进程匹配路由规则"
        )
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun registerMyInterface(name: String) = Unit

    override fun sendNotification(notification: Notification) = Unit

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        throw UnsupportedOperationException("V2compose 内嵌 sing-box 不支持 shell 会话")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession {
        throw UnsupportedOperationException("V2compose 内嵌 sing-box 不支持 shell 会话")
    }

    override fun lookupUser(username: String): PlatformUser {
        throw UnsupportedOperationException("V2compose 内嵌 sing-box 不支持用户查询")
    }

    override fun lookupSFTPServer(): String {
        throw UnsupportedOperationException("V2compose 内嵌 sing-box 不支持 SFTP")
    }

    override fun readSystemSSHHostKey(): String {
        throw UnsupportedOperationException("V2compose 内嵌 sing-box 不支持 SSH")
    }

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession {
        throw UnsupportedOperationException("V2compose 内嵌 sing-box 不支持 bridge")
    }

    // endregion

    /**
     * 生成 libbox 需要的 "地址/前缀长度"。
     *
     * 注意：IPv6 的 hostAddress 会带 scope id（例如 fe80::c43d:a0ff:feb6:26e2%rmnet_data4），
     * 而 sing-box 内部用 netip.MustParsePrefix 解析该字符串，**带 zone 会 panic 直接杀掉进程**
     * （真机崩溃栈：libbox.(*platformInterfaceWrapper).NetworkInterfaces → service.go:142）。
     * 因此这里必须去掉 zone。
     */
    private fun InterfaceAddress.toPrefix(): String? {
        val hostAddress = address.hostAddress?.substringBefore('%') ?: return null
        return hostAddress + "/" + networkPrefixLength
    }

    private companion object {
        const val DEFAULT_MTU = 1500
        const val TAG = "SingBoxPlatform"
    }
}
