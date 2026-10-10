# shared/libs

这里放置内嵌 sing-box 核心产物 `libbox.aar`（由 gomobile 从 sing-box 源码构建）。

该文件体积较大（数十 MB），因此**不纳入版本控制**（见 `.gitignore`）。
克隆仓库后、构建 Android 之前需要先生成：

```bash
ANDROID_HOME=/path/to/android-sdk \
ANDROID_NDK_HOME=/path/to/android-ndk \
./scripts/build-libbox.sh
```

脚本细节见 `scripts/build-libbox.sh` 与 `docs/plans/codex/codex-singbox-proxy-plan.md`。
