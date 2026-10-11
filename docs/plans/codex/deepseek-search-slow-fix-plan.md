# 搜索缓慢甚至不出结果

## 现象

在搜索页输入关键词（如「流量卡」）并回车后，页面长时间停留在「历史搜索」界面，
没有加载指示、没有错误提示、也没有任何结果；重试（再次回车/点击历史词）也可能毫无反应。

## 根因

搜索链路是：`SearchScreen` → `SearchViewModel.topics` → `DefaultTopicRepository.search` →
`SearchPagingSource` → `V2exApi.search` → `https://www.sov2ex.com/api/search`。
逐段排查后确认问题不在单个环节，而是几类缺陷叠加，其中前两类直接造成「不出结果」的观感。

### 1. UI 把「加载中 / 加载失败 / 无结果」全部当成空列表（主因）

`SearchScreen` 原来只用 `topics.itemSnapshotList.isEmpty()` 二选一：

```kotlin
if (topics.itemSnapshotList.isEmpty()) SearchHistoryKeywords(...) else SearchResult(...)
```

- 首屏请求进行中 → 列表为空 → 展示历史搜索词，**没有任何加载指示**（观感就是“慢”）；
- 首屏请求失败（网络错误 / 超时 / HTTP 400 / 3xx）→ 列表仍为空 → 依然展示历史搜索词，
  **错误被完全吞掉，且重试入口 `pagingRefreshItem` 在 `SearchResult` 内部，永远不会被渲染**，
  用户被永久卡在“没有结果”；
- 请求成功但该关键词 total=0 → 同样是空列表 → 展示历史搜索词，无法区分“没搜”和“没搜到”。

### 2. 同一个关键词再搜一次不会重新请求

`SearchViewModel` 用 `keyword.filterNot { it.isNullOrEmpty() }.flatMapLatest { ... }` 驱动请求，
而 `keyword` 是 `MutableStateFlow`。`StateFlow` 会丢弃与当前值相等的值，因此
**再次搜索相同关键词（上次失败后重试、手动刷新）不会重新发起请求**，页面保持原样。

### 3. 搜索请求没有超时

`createConfiguredHttpClient` 没有安装 Ktor 的 `HttpTimeout` 插件。Android 侧 OkHttp 有默认超时，
但 **iOS 的 Darwin 引擎默认 `timeoutIntervalForRequest` 为 60s**，弱网/服务无响应时搜索要挂起很久，
期间页面又没有任何反馈（见根因 1）。

### 4. 翻页会翻到服务端的硬限制之外

对线上接口实测（2026-10-11）：

| 请求 | 结果 |
| --- | --- |
| `size=50` | 200 |
| `size=51..100` | 400 `{"message":"too large size"}` |
| `from=990&size=10`（from+size=1000） | 200 |
| `from=1000&size=10` | 400 `{"message":"too deep paging"}` |

而 `q=v2ex` 的 total=56301、`q=流量卡` 的 total=154625。
`SearchPagingSource` 之前把 `resultInfo.total` 当作可翻页上限，于是列表会一直翻到
`from` 超过 1000 后开始持续 400，再叠加根因 1/2，表现为“搜索坏了”。

### 5. 首屏请求量偏大，首屏更慢

同一关键词的响应时间随 size 明显增长（各 7 次取中位数）：

- `q=流量卡`：size=10 → 228ms、size=20 → 263ms、size=30 → 311ms；
- `q=v2ex`：size=10 → 237ms、size=20 → 465ms、size=30 → 546ms。

Paging 默认 `initialLoadSize = pageSize * 3`，所以首屏实际请求 30 条，白等约一倍时间。

### 6. 第三方搜索域名受 V2 客户端重定向策略影响（隐患）

上一次修复 `fix(network): 重定向跟随策略按客户端区分` 中有一句结论：“`v2HttpClient()` 全仓库仅
`V2exApi` 使用，因此关闭跟随只影响 V2EX 接口”。但 `V2exApi.search` 请求的其实是第三方域名
`www.sov2ex.com`，**同样受 `followRedirects=false` 影响**：一旦该域名返回 301/302
（http→https、apex→www、维护跳转等），搜索会直接抛 3xx 异常，配合根因 1 静默失败。
需要说明：本次实测 `https://www.sov2ex.com/api/search` 直连返回 200，因此这属于隐患而非当前主因，
但必须补上，否则重定向策略的改动会持续埋雷。

## 修改

- `shared/.../network/Sov2exSearch.kt`（新增）
  - 抽出搜索地址构造、重定向解析（与 Ktor `HttpRedirect` 一致：清空原 query 后 `takeFrom(Location)`）、
    `getFollowingRedirects`（手动跟随最多 3 次 + 每次请求 15s 超时）；
  - 非 2xx 抛出携带状态码与服务端 message 的 `Sov2exSearchException`，供 UI 展示可读原因。
- `shared/.../network/V2exApi.kt`
  - `search()` 改用 `getFollowingRedirects(buildSov2exSearchUrl(...))`。
- `shared/.../network/V2Client.kt`
  - 安装 `HttpTimeout` 插件（具体超时按请求指定），搜索请求使用 15s。
- `shared/.../datasource/SearchPagingSource.kt`
  - `size` 钳制到服务端上限 50；`from + size` 超过 1000 时直接结束分页、不再发请求；
  - `nextKey` 仅在下一请求仍落在窗口内时给出；
  - `prevKey` 置空（搜索只向后翻页，避免 Paging 触发重复 preload）；
  - 放行 `CancellationException`，不再把协程取消当成加载失败。
- `shared/.../ui/search/SearchViewModel.kt`
  - 用 `MutableSharedFlow(replay = 1)` 驱动搜索请求，相同关键词也会重新发起；
  - 通过 `SavedStateHandle` 带入的关键词在 `init` 中触发首次搜索。
- `shared/.../ui/search/SearchScreen.kt`
  - 新增 `SearchContentState` + `searchContentState(keyword, refresh, itemCount)` 纯函数，
    区分 History / Loading / Error / Empty / Results；
  - 加载中与失败复用 `PagingLoadState`（失败带「重试」按钮），无结果展示新增的空状态文案。
- `shared/.../repository/def/DefaultTopicRepository.kt`
  - `PagingConfig(pageSize = 10, initialLoadSize = 10)`，首屏只请求 10 条（原先默认 30 条），
    实测可把首屏等待从约 300–550ms 降到约 230ms。
- `shared/src/commonMain/composeResources/values/strings.xml`
  - 新增 `search_no_results`（没有找到相关主题）。

## 影响面

- 只影响搜索链路；V2EX 业务动作的 302 语义、图片客户端跟随策略均未改动。
- `HttpTimeout` 以“只安装、不设全局默认值”的方式引入，其他 V2EX 请求的超时行为不变。
- `SearchPagingSource` 的 `prevKey` 由非空改为 null：搜索是前向分页，刷新键本就返回 null，
  该改动消除潜在的重复 preload。

## 验证

- 新增 Android host 单测（`./gradlew :androidApp:testFossDebugUnitTest`）：
  - `Sov2exSearchTest`：参数拼接、相对/绝对重定向跟随、重定向次数上限、非 2xx 可读错误；
  - `SearchPagingSourceTest`：size 钳制、窗口外不发请求、窗口边界停止翻页、错误与取消的传播；
  - `SearchContentStateTest`：加载中 / 失败 / 无结果 / 已有结果各状态映射。
- 全量（本机 `ANDROID_HOME=/opt/android-sdk`）：
  - `./gradlew :androidApp:testFossDebugUnitTest` → BUILD SUCCESSFUL；
    `Sov2exSearchTest` 6/6、`SearchPagingSourceTest` 6/6、`SearchContentStateTest` 5/5、
    既有 `V2exActionRedirectTest` 2/2 全部通过，0 failures；
  - `./gradlew :androidApp:assembleFossDebug` → BUILD SUCCESSFUL，产出 debug APK；
  - `./gradlew :shared:compileKotlinIosSimulatorArm64` → BUILD SUCCESSFUL。
- 说明：仓库未给 `:shared` 配置 Android host test，本机也没有 macOS/iOS 模拟器，
  因此新增测试放在 `androidApp/src/test`（仓库既有 JVM 单测的落地位置）。
