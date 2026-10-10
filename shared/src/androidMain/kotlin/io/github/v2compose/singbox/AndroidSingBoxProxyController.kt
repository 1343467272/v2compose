package io.github.v2compose.singbox

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.v2compose.network.SingBoxProxyController
import io.github.v2compose.network.SingBoxState
import io.github.v2compose.shared.bean.ProxyInfo
import io.github.v2compose.shared.bean.ProxyType
import io.github.v2compose.shared.singbox.SingBoxConfig
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SingBoxProxy"

/** 本地 mixed 入站的起始端口，实际端口由 libbox 挑选可用的一个。 */
private const val FIRST_LOCAL_PORT = 20800

/**
 * 基于 libbox 的内嵌 sing-box 核心。
 *
 * 核心运行在 App 进程内，只提供 127.0.0.1 上的 mixed（HTTP + SOCKS5）入站，
 * App 自身流量（接口请求、图片、内嵌网页）通过该端口转发，因此不需要 VPN 权限，
 * 也不会影响设备上的其它应用。
 */
class AndroidSingBoxProxyController(
    private val context: Context,
) : SingBoxProxyController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<SingBoxState>(SingBoxState.Stopped)
    override val state: StateFlow<SingBoxState> = _state.asStateFlow()

    @Volatile
    override var localPort: Int? = null
        private set

    private val platformInterface = SingBoxPlatformInterface(context)

    /** 最近一次请求的配置；null 表示应当停止核心。跨线程读写。 */
    @Volatile
    private var requestedConfig: String? = null

    private var commandServer: CommandServer? = null
    private var runningConfig: String? = null
    private var setupDone = false

    private val handler = object : CommandServerHandler {

        override fun serviceStop() {
            requestedConfig = null
            scope.launch {
                mutex.withLock { stopLocked() }
            }
        }

        override fun serviceReload() {
            val config = runningConfig ?: return
            requestedConfig = config
            scope.launch {
                mutex.withLock { startLocked(config) }
            }
        }

        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
            available = false
            enabled = false
        }

        override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

        override fun triggerNativeCrash() {
            throw RuntimeException("debug native crash")
        }

        override fun writeDebugMessage(message: String?) {
            Log.d(TAG, message.orEmpty())
        }

        override fun connectSSHAgent(): Int = -1
    }

    override fun apply(proxyInfo: ProxyInfo) {
        val config = if (proxyInfo.type == ProxyType.SingBox) {
            proxyInfo.singboxConfig
        } else {
            null
        }
        requestedConfig = config
        scope.launch {
            mutex.withLock {
                val requested = requestedConfig
                when {
                    requested == null -> stopLocked()
                    commandServer != null && runningConfig == requested -> Unit
                    else -> {
                        val error = SingBoxConfig.validate(requested)
                        if (error != null) {
                            stopLocked()
                            _state.value = SingBoxState.InvalidConfig(error)
                        } else {
                            startLocked(requested)
                        }
                    }
                }
            }
        }
    }

    override fun stop() {
        requestedConfig = null
        scope.launch {
            mutex.withLock { stopLocked() }
        }
    }

    private fun startLocked(config: String) {
        stopLocked()
        _state.value = SingBoxState.Starting
        try {
            ensureSetup()
            val port = Libbox.availablePort(FIRST_LOCAL_PORT)
            val built = SingBoxConfig.buildLocalMixedConfig(config, port)
            if (built is SingBoxConfig.Result.Failure) {
                _state.value = SingBoxState.InvalidConfig(built.error)
                return
            }
            val content = (built as SingBoxConfig.Result.Success).content
            val server = CommandServer(handler, platformInterface)
            server.start()
            server.startOrReloadService(content, OverrideOptions())
            commandServer = server
            runningConfig = config
            localPort = port
            _state.value = SingBoxState.Running(port)
            Log.d(TAG, "sing-box started, local mixed inbound on 127.0.0.1:$port")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start sing-box core", t)
            runCatching { commandServer?.closeService() }
            runCatching { commandServer?.close() }
            commandServer = null
            runningConfig = null
            localPort = null
            _state.value = SingBoxState.StartFailed(t.message)
        }
    }

    private fun stopLocked() {
        val server = commandServer
        commandServer = null
        runningConfig = null
        localPort = null
        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "close sing-box service failed", it) }
            runCatching { server.close() }
        }
        _state.value = SingBoxState.Stopped
    }

    private fun ensureSetup() {
        if (setupDone) return
        val baseDir = context.filesDir
        val workingDir = context.getExternalFilesDir(null) ?: baseDir
        workingDir.mkdirs()
        val options = SetupOptions().apply {
            basePath = baseDir.absolutePath
            workingPath = workingDir.absolutePath
            tempPath = context.cacheDir.absolutePath
            fixAndroidStack = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            logMaxLines = 2000
            debug = false
            crashReportSource = "V2compose"
            platformMetadata = buildString {
                append("{\"os\":\"Android\",")
                append("\"sdk\":")
                append(Build.VERSION.SDK_INT)
                append(",\"manufacturer\":\"")
                append(Build.MANUFACTURER)
                append("\",\"model\":\"")
                append(Build.MODEL)
                append("\"}")
            }
        }
        Libbox.setup(options)
        setupDone = true
    }
}
