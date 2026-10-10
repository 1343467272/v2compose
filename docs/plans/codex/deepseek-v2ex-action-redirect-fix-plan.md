# V2EX 业务动作 302 被自动跟随导致 Fruit 解析崩溃

## 现象

在主题页执行「感谢 / 收藏 / 忽略」等操作时，Snackbar 弹出：

```
Fruit cannot handle class io.github.v2compose.network.bean.V2exResult
(Kotlin reflection is not available). No factory found.
```

## 根因

- V2EX 的业务动作接口（`/{action}/topic/{id}`、`/{action}/reply/{id}`，以及 `/favorite/*`、`/follow/*` 等）成功时返回
  `302` 重定向，项目所有上层逻辑（`TopicViewModel.doTopicAction/doReplyAction`、
  `WriteTopicViewModel.createTopic`、`AddSupplementViewModel.addSupplement`、`DefaultUserRepository.doUserAction`、
  `DefaultNodeRepository.doNodeAction`、`CheckInUseCase`、`UpdateAccountUseCase.updateWithException`）
  都通过 `Exception.isRedirect`（`ResponseException` 且 `3xx`）来处理这类业务重定向。
- Retrofit 时代 OkHttp 层设置了 `followRedirects(false)`，并由 `ParserInterceptor` 把 302 转换成合成 JSON。
  迁移到 Ktor 后 `ParserInterceptor` 被移除，改为依赖上述 `isRedirect` 逻辑，但 Ktor 客户端
  `followRedirects` 仍是默认值 `true`（`HttpClient` 会自动安装 `HttpRedirect` 插件），
  于是 302 被自动跟随到目标 HTML 页面。
- 跟随后的 200 `text/html` 响应经 ContentNegotiation 交给 `FruitContentConverter`，
  而动作接口声明的返回类型 `V2exResult` 是 `@Serializable` 数据类、没有 `@Slice` 适配器，
  于是 `Fruit.getSliceAdapter` 抛出 `IllegalArgumentException`，最终以原始异常文案出现在 Snackbar 中。

## 关键约束：重定向策略必须按客户端区分

`V2Client.kt` 里的 `createConfiguredHttpClient` 同时服务 V2 客户端与图片客户端，因此不能全局关闭跟随：

| 客户端 | 引擎 | 期望行为 |
| --- | --- | --- |
| V2 (`createV2HttpClient`) | Android: `CommonOkHttpClient`（OkHttp `followRedirects(false)`）；iOS: `Darwin` | **不跟随**，3xx 抛给 `isRedirect` |
| 图片 (`createImageHttpClient`) | Android: `ImageOkHttpClient`（OkHttp `followRedirects(true)`）；iOS: `Darwin` | **跟随**（CDN http->https、imgur 页面跳转；`ExternalImageUrlResolver` 依赖最终 URL） |

Ktor 的 Darwin 引擎在 `URLSession:task:willPerformHTTPRedirection:` 中直接 `completionHandler(null)`，
即**引擎层不跟随、完全由 `HttpRedirect` 插件决定**。因此若在公共工厂里统一关闭跟随，
Android 图片因 OkHttp 自行跟随而"看起来正常"，iOS 图片则会被静默破坏——必须按客户端显式指定。

## 修改

- `shared/src/commonMain/kotlin/io/github/v2compose/network/V2Client.kt`
  - `createConfiguredHttpClient` 新增 `followRedirects: Boolean` 参数并按客户端传入：
    V2 客户端 `false`，图片客户端 `true`。
- `shared/src/androidMain/kotlin/io/github/v2compose/network/PlatformEngine.android.kt`
  - 兜底引擎 `OkHttp.create()` 关闭引擎级跟随，保证"不传 engine"的路径同样由 `HttpRedirect` 插件统管。
- `shared/src/androidMain/kotlin/io/github/v2compose/core/Platform.kt`
  - `deviceModel()` 兼容 `Build.MODEL` 为 null 的宿主环境（纯 JVM 单测），避免 `NetConstants` 初始化失败。
- `androidApp/build.gradle.kts`
  - 新增 `testImplementation(libs.ktor.client.okhttp)` 与 `testOptions.unitTests.isReturnDefaultValues = true`，
    使共享网络客户端可以在 Android host 单测中运行。
- `androidApp/src/test/java/io/github/v2compose/network/V2exActionRedirectTest.kt`
  - 本地 `HttpServer` 模拟 `/thank/reply/1 -> 302 /t/1`、`/img/1 -> 302 /img/final`；
    引擎层设为不跟随以单独验证 Ktor 客户端策略：
    V2 客户端只请求一次并抛 `3xx` `ResponseException`；图片客户端跟随到 `/img/final` 并返回 200。

## 影响面

- `v2HttpClient()` 全仓库仅 `V2exApi` 使用，因此关闭跟随只影响 V2EX 接口，正是期望的语义。
- `githubHttpClient()`（GitHub API / sing-box 订阅）与图片客户端行为保持不变。

## 验证

- 修复前：`./gradlew :androidApp:testFossDebugUnitTest --tests "*V2exActionRedirectTest"` 失败，
  异常与截图一致。
- 修复后：V2 与图片两个用例均通过（`tests=2 failures=0`）。
- `./gradlew :androidApp:testFossDebugUnitTest :androidApp:assembleFossRelease` 通过并产出已签名 Release APK。
- `adb install -r` 覆盖安装到已登录设备，启动无崩溃。
