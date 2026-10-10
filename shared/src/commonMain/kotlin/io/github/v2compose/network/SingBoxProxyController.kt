package io.github.v2compose.network

import io.github.v2compose.shared.bean.ProxyInfo
import io.github.v2compose.shared.singbox.SingBoxConfig
import kotlinx.coroutines.flow.StateFlow

/** 内嵌 sing-box 核心的运行状态 */
sealed interface SingBoxState {

    /** 未启用 / 已停止 */
    data object Stopped : SingBoxState

    /** 正在启动 */
    data object Starting : SingBoxState

    /** 运行中，[port] 为本地 mixed 入站端口 */
    data class Running(val port: Int) : SingBoxState

    /** 配置不合法，核心未启动 */
    data class InvalidConfig(val error: SingBoxConfig.Error) : SingBoxState

    /** 启动失败（核心抛出的异常） */
    data class StartFailed(val message: String?) : SingBoxState

    /** 当前平台不支持内嵌 sing-box（如 iOS） */
    data object Unsupported : SingBoxState
}

/**
 * 内嵌 sing-box 核心控制器。
 *
 * 核心以本地 mixed 入站（127.0.0.1）方式运行在 App 进程内，
 * App 自身流量（接口请求、图片、内嵌网页）通过该端口转发，无需 VPN 权限。
 */
interface SingBoxProxyController {

    /** 当前运行状态，用于设置页展示 */
    val state: StateFlow<SingBoxState>

    /**
     * 本地 mixed 入站端口；核心未运行时为 null。
     * 会被网络层（ProxySelector）从任意线程读取，实现需保证可见性。
     */
    val localPort: Int?

    /** 根据代理设置启动或停止核心。重复调用相同配置应当是幂等的。 */
    fun apply(proxyInfo: ProxyInfo)

    /** 停止并释放核心。 */
    fun stop()
}
