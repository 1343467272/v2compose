# V2compose 内嵌 sing-box 代理实施计划

- 状态：已实施（Android 可用，iOS 仅保留占位实现）
- 目标平台：Android（minSdk 26）；本次只改安卓端，iOS 未做功能实现
- sing-box 版本：v1.14.3（libbox，gomobile 绑定）

## 1. 需求

在 App 内部直接使用 sing-box 代理：用户粘贴一份 sing-box config JSON，或粘贴订阅链接/分享链接，
App 启动内嵌的 sing-box 核心，自身流量（接口请求、图片、内嵌网页）经由该核心转发；订阅支持自动更新。
不再需要额外安装 VPN / 代理客户端，也不需要 VPN 权限。

## 2. 方案选择

| 方案 | 说明 | 结论 |
| --- | --- | --- |
| A. 内嵌 sing-box + 本地 mixed 入站 | 核心跑在 App 进程内，仅监听 127.0.0.1，App 自身流量走 HTTP/SOCKS 代理 | **采用** |
| B. 内嵌 sing-box + TUN/VPN 模式 | 通过 VpnService 接管整机流量 | 后续可选扩展 |

选择 A 的原因：

1. 与现有代理架构（ProxySelector + WebView ProxyController）完全吻合，改动面小；
2. 不需要 BIND_VPN_SERVICE / 前台服务 / VPN 授权弹窗，也不影响设备上其它应用；
3. 不需要实现 libbox 的 openTun，只用到 PlatformInterface 的一小部分能力。

方案 B 的增量工作是：实现 PlatformInterface.openTun（VpnService.Builder + tun fd）、前台服务与通知、
以及在配置中注入 tun 入站。代码结构已为此预留（配置改写集中在 SingBoxConfig，平台实现集中在 SingBoxPlatformInterface）。

## 3. 架构

```
设置页 ——(ProxyInfo: SingBox + config JSON + 订阅链接)——> AppPreferences
        |
        +--> MainViewModel/SettingsViewModel --> SingBoxProxyController.apply()
                                                    |
                                      AndroidSingBoxProxyController
                                        - Libbox.setup(SetupOptions)
                                        - Libbox.availablePort() 选端口
                                        - SingBoxConfig.buildLocalMixedConfig()
                                        - CommandServer.startOrReloadService()
                                                    |
                                    本地 mixed 入站 127.0.0.1:<port>
                                                    ^
V2ProxySelector(OkHttp) ----------------------------+
AndroidWebViewProxyController ----------------------+
```

关键点：

- ProxyInfo 增加 ProxyType.SingBox、singboxConfig、singboxSubscriptionUrl、singboxSubscriptionUpdatedAt（向后兼容旧数据）。
- V2ProxySelector 在 sing-box 模式下把流量指向 127.0.0.1:<port>（HTTP 代理，sing-box 的 mixed 入站同时支持 HTTP 与 SOCKS5）。
  核心未运行时回退直连，避免整个 App 无法联网。
- AndroidWebViewProxyController 同理把 WebView 的 ProxyController 指向本地端口。
- MainViewModel 同时观察「代理配置」与「核心状态」，核心就绪后再刷新 WebView 代理。

## 4. 配置改造规则（SingBoxConfig）

用户提供的是普通 sing-box config（手工粘贴或订阅解析结果），启动前会被改写：

1. inbounds 被替换为唯一的本地 mixed 入站：
   {"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":<port>}；
2. route.auto_detect_interface 强制为 false（非 VPN 场景由系统选路）；
3. 显式引用其它入站（如 tun-in）的 route.rules 会被移除，避免核心启动报「入站不存在」；
4. outbounds 必须存在且非空，否则在 UI 上直接报错。

## 5. 订阅链接与自动更新

### 5.1 支持的订阅内容（SingBoxSubscriptionParser）

1. 完整的 sing-box config JSON（含 outbounds）——直接使用；
2. 纯文本分享链接列表（每行一条）；
3. 链接列表的 Base64 编码（机场通用订阅格式）。

分享链接解析（SingBoxShareLink）支持 vless / vmess / ss / trojan / hysteria2（hy2），
包括 tls / reality / utls、ws / grpc / http / httpupgrade / quic 传输与 hysteria2 obfs。
多节点时生成 selector 出站（tag=proxy）作为 route.final，单节点时直接作为 final。

### 5.2 拉取

DefaultSingBoxSubscriptionFetcher 复用已有的跨平台 Ktor 客户端（NetworkClientProvider.githubHttpClient()），
该客户端基于带 V2ProxySelector 的 OkHttp，因此会遵循当前代理设置。

### 5.3 自动更新

RefreshSingBoxSubscriptionUseCase 在应用启动时（MainViewModel init）检查：
若当前为 sing-box 模式、订阅链接非空、且距上次更新超过 6 小时，则重新拉取并写回 ProxyInfo；
配置写回后由 MainViewModel 的观察链路自动重启/重载核心，无需手动操作。

## 6. 文件清单

新增：

- shared/src/commonMain/.../shared/singbox/SingBoxConfig.kt（配置改写）
- shared/src/commonMain/.../shared/singbox/SingBoxShareLink.kt（分享链接解析）
- shared/src/commonMain/.../shared/singbox/SingBoxSubscription.kt（订阅内容解析）
- shared/src/commonMain/.../network/SingBoxProxyController.kt（核心接口与状态）
- shared/src/commonMain/.../network/SingBoxSubscriptionFetcher.kt
- shared/src/commonMain/.../usecase/RefreshSingBoxSubscriptionUseCase.kt
- shared/src/commonMain/.../ui/settings/SingBoxImportState.kt
- shared/src/androidMain/.../singbox/AndroidSingBoxProxyController.kt
- shared/src/androidMain/.../singbox/SingBoxPlatformInterface.kt
- shared/src/androidMain/.../singbox/SingBoxNetworkMonitor.kt
- shared/src/androidMain/.../singbox/SingBoxIterators.kt
- shared/src/commonTest/.../shared/singbox/SingBoxConfigTest.kt
- shared/src/commonTest/.../shared/singbox/SingBoxSubscriptionTest.kt
- scripts/build-libbox.sh

修改（Android 端）：

- 设置页：SelectProxyDialog（代理类型下拉 + 订阅导入 + 配置编辑）、SettingsScreen（状态摘要）、SettingsViewModel、
  strings.xml；
- 网络层：V2ProxySelector、AndroidWebViewProxyController、di/PlatformModule.kt（Koin 绑定）、di/Koin.kt（订阅相关绑定）；
- MainViewModel（应用启动时启动核心 + 自动更新订阅）；
- shared/build.gradle.kts（libbox.aar 依赖）、androidApp/src/main/AndroidManifest.xml（ACCESS_NETWORK_STATE）；
- .gitignore、README.md。

iOS：仅保留编译所需的占位实现（NoOpSingBoxProxyController + 一个 when 分支），无功能改动。

## 7. 构建内嵌核心

libbox.aar 由 sing-box 源码通过 gomobile 构建，体积较大，不纳入版本控制：

```bash
ANDROID_HOME=/path/to/android-sdk \
ANDROID_NDK_HOME=/path/to/ndk \
./scripts/build-libbox.sh
```

脚本会克隆 SagerNet/sing-box 指定 tag，执行 go run ./cmd/internal/build_libbox -target android，
并把产物复制到 shared/libs/libbox.aar。需要 Go >= 1.24、JDK 17、Android NDK。

注：全 ABI 构建在当前 Go 1.26 环境下 386/amd64 会触发编译器 ICE，当前仓库内产物为 arm64-v8a 单 ABI。

## 8. 已知限制

- 只代理 App 自身流量；不接管整机流量（如需，见方案 B）。
- 不支持按进程/包名匹配的路由规则（findConnectionOwner 未实现）。
- 不支持配置中的 tun 入站（会被替换）、tailscale、shell、bridge 等平台能力。
- DNS 走 Android 系统解析器（SingBoxLocalResolver，非 raw DNS）。
- 订阅只支持拉取后生成配置，不含机场流量/到期信息展示。
- iOS 暂不支持，选择 sing-box 时按直连处理并在设置页提示。
- 未在真机上做运行时验证（本环境无 Android 设备/模拟器），验证范围为编译与单元测试。

## 9. 验证

```bash
# 内嵌核心（需要先执行 scripts/build-libbox.sh）
./gradlew :androidApp:assembleFossDebug
```

纯 Kotlin 解析逻辑的 26 个单元测试（SingBoxConfig / SingBoxShareLink / SingBoxSubscription /
ProxyInfo 序列化兼容）在 JVM 沙箱工程中执行通过（本仓库的 shared 模块在当前环境没有可运行的
Android 单元测试任务，iosSimulatorArm64Test 无法在 Linux 上执行）。
## 10. 已知问题与修复记录

### 10.1 导入订阅后打开 App 闪退（已修复）

- 现象：导入订阅（即启用内嵌核心）后，App 启动即崩溃，且每次打开都闪退。
- 原因：`SetupOptions.fixAndroidStack` 被错误地设置为「仅 Android O 以下开启」。libbox 的
  `platformDefaultInterfaceMonitor.UpdateDefaultInterface` 在该开关关闭时会**直接在 Java 回调线程上执行 Go 代码**；
  该线程栈由 Java（ConnectivityManager）创建、不受 Go runtime 管理，在 Android 上会导致进程崩溃
  （golang/go#68760）。官方 Android 客户端的取值是 `SDK 24–25 或 SDK >= 28` 时为 true。
- 修复：
  1. `fixAndroidStack` 与官方实现保持一致（`SDK_INT <= 25 || SDK_INT >= 28`）；
  2. `SingBoxNetworkMonitor` 把网络回调统一切到独立线程后再回调 libbox，避免在 ConnectivityManager 线程直接进 Go；
  3. `PlatformInterface.getInterfaces()`、`LocalDNSTransport` 等回调不再向 Go 抛异常（跨 JNI 抛异常可能升级为 Go panic），
     失败时返回空列表 / DNS 错误码；
  4. 补充 R8 keep 规则：`-keep class io.github.v2compose.singbox.** { *; }`。
- 兜底保护：新增崩溃计数（`AppPreferences.singbox_start_attempts`）。核心启动前 +1，启动成功或捕获到异常后清零；
  连续两次「未确认启动」说明进程被核心带崩，此时不再自动启动核心，App 可正常打开，用户在设置页重新确认即可重置计数。
