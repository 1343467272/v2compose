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

## 修改

- `shared/src/commonMain/kotlin/io/github/v2compose/network/V2Client.kt`
  - 在 `createConfiguredHttpClient` 中显式设置 `followRedirects = false`，
    让 V2EX 的 3xx 直接以 `ResponseException` 抛出，交由既有 `isRedirect` 逻辑处理。
- `shared/src/androidMain/kotlin/io/github/v2compose/core/Platform.kt`
  - `deviceModel()` 兼容 `Build.MODEL` 为 null 的宿主环境（纯 JVM 单测），避免 `NetConstants` 初始化失败。
- `androidApp/build.gradle.kts`
  - 新增 `testImplementation(libs.ktor.client.okhttp)` 与 `testOptions.unitTests.isReturnDefaultValues = true`，
    使共享网络客户端可以在 Android host 单测中运行。
- `androidApp/src/test/java/io/github/v2compose/network/V2exActionRedirectTest.kt`
  - 新增回归测试：本地 `HttpServer` 模拟 `/thank/reply/1 -> 302 /t/1` 与目标 HTML 页面，
    断言客户端不会跟随重定向、只发出一次请求，并抛出 `3xx` 的 `ResponseException`。
    修复前该用例收到的正是 `Fruit cannot handle class ...V2exResult ... No factory found.`。

## 验证

- 修复前：`./gradlew :androidApp:testFossDebugUnitTest --tests "*V2exActionRedirectTest"` 失败，
  异常与截图一致。
- 修复后：同一用例通过。
- `./gradlew :androidApp:testFossDebugUnitTest :androidApp:assembleFossRelease` 通过并产出已签名 Release APK。
- `adb install -r` 覆盖安装到已登录设备验证。
