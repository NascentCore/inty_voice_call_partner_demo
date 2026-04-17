# Inty Voice Call 示例工程（可打开项目）

本目录是一个 **Android Studio 工程根目录**，与上级 **`inty_voice_call/`** 目录**同级**。`settings.gradle.kts` 通过 `include(":inty_voice_call")` 引用 **原始 `inty_voice_call` 库源码**（与 IntelliMate 仓库 `android_app/library/inty_voice_call/src` 一致；对接包中的 `build.gradle.kts` 已换为 standalone，便于脱离主工程编译）。

## 如何打开

1. 解压交付包后保持目录结构：`inty_voice_call_partner/demo/` 与 `inty_voice_call_partner/inty_voice_call/` 并存。
2. 启动 **Android Studio**，选 **Open**，指向 **`demo`** 文件夹（不要只选 `app`）。
3. 若提示缺少 SDK：在本目录创建 `local.properties`，写入一行 `sdk.dir=/你的/Android/sdk`（或设置环境变量 `ANDROID_HOME`）。
4. **Sync Project**，运行 **`app`** 模块。启动页会调用 `IntyVoiceCallUrls.liveChatWebSocketUrl` 证明模块已正确链接；真实联调请自行接入 Ktor `HttpClient`、麦克风与播放（可参考上级 **`examples/AndroidKtorMinimal.kt`**）。

## 命令行编译（可选）

```bash
cd demo
./gradlew :app:assembleDebug
```

联调效果录像见上级 **`assets/`**（如 `demo.mp4`），说明见 **`对接文档.md`** 第 4.6 节。
