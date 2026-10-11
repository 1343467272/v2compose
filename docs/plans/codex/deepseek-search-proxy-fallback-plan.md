# 搜索在代理链路下失败：改为直连兜底

## 现象

Release 包安装到真机（PHK110）后，搜索页仍然取不到结果：界面显示错误与「重试」，
但反复重试都不出内容。设置里开启了「内嵌 sing-box」代理。

## 真机取证（2026-10-11）

| 检查项 | 结果 |
| --- | --- |
| App 请求 `https://www.sov2ex.com/api/search` | 每次 `javax.net.ssl.SSLHandshakeException: connection closed` |
| 同一台手机 `curl` 直连该接口 | **HTTP 200，1.15s，14,268 字节（total=14538）** |
| App 请求 `https://www.v2ex.com/t/...` | 200 |
| App 请求 `https://cdn.v2ex.com/...` | 200 |
| App 代理配置 | 内嵌 sing-box mixed 入站 `127.0.0.1:20800`，App 全部流量都走它 |
| 直连 `www.v2ex.com` | 超时（必须走代理） |

结论：**代理链路（节点出站）无法与国内可直连的 `www.sov2ex.com` 完成 TLS 握手**，
而同一台设备的直连完全正常。原实现把 App 全部流量交给代理，于是「一开代理就搜不到」。

## 修改

- `shared/.../network/Sov2exSearch.kt`
  - 新增 `HttpClient.searchWithDirectFallback(directClientProvider, url, timeoutMillis)`：
    - 先走当前客户端（可能是代理）；
    - 链路类失败（连接/TLS/超时等 `Exception`）时，改为直连再试一次；
    - `CancellationException` 继续上抛；
    - `Sov2exSearchException`（服务端已响应：4xx/5xx、重定向次数超限）不回退，避免重复打服务；
    - 回退时会打一条 `KLogger` 便于排查。
- `shared/.../network/V2exApi.kt`
  - 新增 `directClientFactory: () -> HttpClient = { createV2HttpClient(engine = createHttpClientEngine()) }`，
    `directClient` 用 `by lazy`，只有真正回退时才创建（正常搜索零额外开销、不新建引擎连接）；
  - `search()` 改用 `searchWithDirectFallback`。
  - 直连客户端使用各平台默认引擎（Android `OkHttp.create`、iOS `Darwin.create`），
    不挂 `V2ProxySelector`，因此是真正的直连。
- `shared/.../di/Koin.kt`
  - `singleOf(::V2exApi)` → `single { V2exApi(get()) }`：默认参数是工厂函数，Koin 无法解析，
    必须显式构造（否则运行到搜索时才会抛解析异常）。

## 影响面

- 仅搜索接口使用兜底；V2EX 自身请求、图片、GitHub、WebView 的代理策略完全不变。
- 语义仍是「代理优先」：只有代理链路确实失败才直连，不会绕过用户配置的代理。
- 直连会把这次搜索请求暴露给 `www.sov2ex.com`（该接口本来就是第三方服务）；
  若用户完全不想直连，应改为修 sing-box 路由（见下）。

## 备选方案（未采用）

在 sing-box 配置的 `route.rules` 最前面加一条规则，让该域名直连、其余仍走出站：

```json
{ "domain_suffix": ["sov2ex.com"], "outbound": "direct" }
```

该方案代码零改动，但依赖用户自行改配置，且新增订阅/换节点后可能再次失效，故未采用。

## 验证

- 单测（`./gradlew :androidApp:testFossDebugUnitTest`）：
  - `searchFallsBackToDirectWhenProxiedAttemptFails`：代理侧抛连接异常时，直连拿到结果；
  - `searchDoesNotFallBackWhenServerAlreadyResponded`：服务端 400 时不直连重试；
  - `directClientIsNotCreatedWhenProxiedSearchSucceeds`：代理可用时不创建直连客户端。
- Release 包安装到真机后在开启 sing-box 的情况下实际搜索，确认能出结果。
