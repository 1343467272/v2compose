# V2Compose

一个 Material You 风格的 V2ex Android 客户端！完全使用 Compose 构建 UI！

该项目是一个练手项目，欢迎大家使用！如有发现体验的问题或者对 Android、Compose 使用不正确的地方，欢迎大家的批评和指正！

该项目已具备V站的大多的日常功能，如果对功能有想法，欢迎提交 pull request 或者 issues。

[<img src="https://raw.githubusercontent.com/cooaer/v2compose/master/.github/images/badge-f-droid.png" alt='Get it on F-Droid' height="80">](https://f-droid.org/packages/io.github.v2compose) [<img src="https://raw.githubusercontent.com/cooaer/v2compose/master/.github/images/badge-github.png" alt='Get it on GitHub' height="80">](https://github.com/cooaer/v2compose/releases/latest)

## 项目特色

* UI 部分完全使用 Compose 实现；
* 简单实现了一个 HtmlText 组件，用于渲染主题和回复中的 Html，相对嵌入一个 WebView 来说效率更高；

## 一些页面预览

| ![news](https://raw.githubusercontent.com/cooaer/v2compose/master/metadata/en-US/images/phoneScreenshots/1.jpg)  | ![topic](https://raw.githubusercontent.com/cooaer/v2compose/master/metadata/en-US/images/phoneScreenshots/2.jpg) | ![nodes](https://raw.githubusercontent.com/cooaer/v2compose/master/metadata/en-US/images/phoneScreenshots/3.jpg)    |
|------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| ![write](https://raw.githubusercontent.com/cooaer/v2compose/master/metadata/en-US/images/phoneScreenshots/4.jpg) | ![mine](https://raw.githubusercontent.com/cooaer/v2compose/master/metadata/en-US/images/phoneScreenshots/5.jpg)  | ![settings](https://raw.githubusercontent.com/cooaer/v2compose/master/metadata/en-US/images/phoneScreenshots/6.jpg) |

## 已完成功能

- [x] 主页：展示一些推荐节点的最新的主题；
- [x] 节点导航页面：展示一些常用的节点；
- [x] 搜索页面：支持搜索话题；
- [x] 主题详情页面：展示主题的详细信息、评论列表；
- [x] 节点详情页面：展示节点的详细信息、该节点下最近回复的主题；
- [x] 用户信息页面：展示用户详细信息、用户创建的主题列表、用户的回复列表；
- [x] 我的页面；
- [x] 设置页面：支持一些简单的设置项；
- [x] 登录；
- [x] 消息通知；
- [x] 创建主题；
- [x] 创建回复；
- [x] 收藏主题，忽略主题，感谢主题，感谢回复，忽略回复；
- [x] 我的节点收藏，我的主题收藏，我的特别关注；

### 特色功能：

- [x] 自动签到；
- [x] 动态主题；
- [x] 代理服务器；
- [x] 内嵌 sing-box 核心（Android，本地 mixed 入站）；
- [x] 高亮OP的回帖；
- [x] 可点击回复中的楼层；
- [x] 主页底部Tab，单击：回到顶部，双击：回到顶部并刷新；

## 用户隐私

V2compose 集成了 Firebase 的 Crashlytics 和 Analytics
功能，仅用于崩溃诊断、稳定性改进与基础使用统计，不用于广告跟踪。
登录、发帖、回帖、签到、搜索等功能会按用户操作与 V2EX 或搜索服务通信；相关隐私说明请参考 `docs/privacy-policy.md`。

## 代理（sing-box）

Android 版本内置了 sing-box 核心（libbox），可以在不安装其它代理客户端、也不需要 VPN
权限的情况下让 App 自身流量走 sing-box：

1. 打开「设置 → 高级 → 代理」；
2. 代理类型选择 **sing-box**；
3. 两种方式二选一：
   - **订阅**：填入订阅链接（机场订阅、Base64 链接列表、或 vless/vmess/ss/trojan/hysteria2 分享链接），点「导入订阅」；
     订阅会在应用启动时按 6 小时间隔自动更新；
   - **手动配置**：直接粘贴一份 sing-box config JSON（会使用其中的 `outbounds`）；
4. 确认即可。

启动时 App 会把配置改写成「本地 mixed 入站」形式（`127.0.0.1` 上的 HTTP + SOCKS5），
接口请求、图片和内嵌网页都会经过该核心；关闭或选择其它代理类型时核心会停止。

已知限制：目前只代理 App 自身流量（不接管整机流量）；不支持配置中的 `tun` 入站、
按进程匹配的路由规则；iOS 暂不支持。

内嵌核心是 Go 编写的 sing-box，通过 gomobile 构建为 `libbox.aar`（体积较大，不纳入版本控制），
构建前需要先执行：

```bash
ANDROID_HOME=/path/to/android-sdk ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-libbox.sh
```

详见 [docs/plans/codex/codex-singbox-proxy-plan.md](docs/plans/codex/codex-singbox-proxy-plan.md)。

## 构建须知

V2compose 集成了 Firebase，开放的源码中不包含 Firebase 的密钥。

如果你需要构建自己的 V2compose 版本：

- Android:
  - 创建自己的 Firebase 应用；
  - 添加 `androidApp/src/google/google-services.json`。
- iOS:
  - 在 Firebase Console 中分别为 bundle id `io.github.v2compose.iosApp` 和 `io.github.v2compose.iosApp.debug` 注册 iOS App；
  - 添加 `iosApp/iosApp/GoogleService-Info.plist`（Release）；
  - 添加 `iosApp/iosApp/GoogleService-Info-Debug.plist`（Debug）；
  - 先执行 `xcodegen generate --spec iosApp/project.yml` 生成工程，再用 Xcode 构建；
  - iOS 侧 Firebase 官方 SDK 通过 Swift Package Manager 接入，最低系统版本为 iOS 15。

## 特别感谢

* [V2er-app/Android](https://github.com/v2er-app/Android) : 一个 Android 端的 V2ex 客户端，V2Compose
  使用该项目的部分网络相关代码！
* [sov2ex](https://github.com/Bynil/sov2ex) : 一个便捷的 V2EX 站内搜索引擎，V2Compose 使用该项目的
  API 实现搜索 V2ex 的功能！

## JetBrains support

Thanks to JetBrains for supporting open source projects.

<a href="https://jb.gg/OpenSourceSupport" target="_blank">https://jb.gg/OpenSourceSupport.</a>

## License

V2compose

Copyright (C) 2023 cooaer

This program is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
