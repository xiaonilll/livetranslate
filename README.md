# 同步翻译 · LiveTranslate

边打字边翻译的 Android 悬浮窗。中文打字 → 英文实时同步出现；退格改字 → 译文跟着改。

## 原理

Android 不允许第三方往别人的输入法键盘里注入 UI，所以「插件」的做法是：

- **无障碍服务**（`InputWatcherService`）读取当前聚焦输入框的文字
- **悬浮窗**（`OverlayService`）贴在键盘上方显示译文
- 300ms 防抖 + 请求取消，避免每个字都打一次接口

## 构建

推送到 `main` 分支，GitHub Actions 自动编译，产物在 Actions → build → Artifacts → `apks`。

## 使用

1. 安装 APK
2. 打开 App → 点「1. 开启无障碍服务」→ 找到「同步翻译 · 输入监听」并开启
3. 回到 App → 点「2. 启动 / 停止悬浮窗」→ 授予悬浮窗权限
4. 去任意输入框打字

## 翻译接口

使用 Google 免费端点 `translate.googleapis.com/translate_a/single`，无需 API key。
