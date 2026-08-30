![HelloXiaoZhi Banner](img/readme_banner.png)

# HelloXiaoZhi

> 基于 [xiaozhi](https://github.com/78/xiaozhi) 协议的 Android 端「小智」AI 语音助手客户端。本项目仅供学习交流使用。

HelloXiaoZhi 是一个运行在 Android 手机上的小智语音助手客户端：像微信一样和小智文字聊天，也能直接语音对话，支持随时打断。项目在无硬件的条件下体验小智的完整对话链路——OTA 设备注册、WebSocket 长连接、Opus 音频编解码、语音打断与状态机切换。

## 功能特性

- **语音通话**：按住即可与小智实时语音对话，支持用户随时打断 AI 回复（abort 机制）
- **文字聊天**：像即时通讯软件一样发送文字消息，AI 回复同时以语音播放
- **OTA 设备注册与激活**：直连官方服务器时自动注册设备，展示 6 位验证码，按提示完成激活
- **双模式连接**：支持官方服务器直连与自定义服务器两种模式，一键切换
- **自动重连**：WebSocket 断线后 3 秒自动重连
- **声浪动效**：语音通话时实时展示用户说话音量波形
- **兼容低版本 Android**：minSdk 21（Android 5.0），并针对低采样率采集设备做了 44.1kHz → 16kHz 线性重采样兜底

## 项目结构

```
HelloXiaoZhi
├── app/                                    # Android 客户端（Kotlin）
│   ├── src/main/
│   │   ├── cpp/                            # C 层 libopus JNI 封装（CMake + NDK）
│   │   │   ├── CMakeLists.txt
│   │   │   └── opus_jni.c
│   │   └── java/org/oxff/helloxiaozhi/
│   │       ├── activation/                 # OTA 注册 + 验证码激活流程编排
│   │       ├── audio/                      # 录音、播放、Opus 编解码、WAV 解析
│   │       ├── chat/                       # 语音状态机、消息模型、聊天数据模型
│   │       ├── config/                     # 应用配置持久化（SharedPreferences）
│   │       ├── controller/                 # 核心编排器（XiaoZhiController）
│   │       ├── net/                        # WebSocket 客户端、OTA HTTP 客户端
│   │       ├── ui/                         # 主界面、设置、语音通话、激活对话框
│   │       ├── util/                       # 工具类（电平计算、设备信息、线程调度）
│   │       └── XiaoZhiApp.kt               # Application 入口
│   └── src/test/                           # JVM 单元测试
│   └── src/androidTest/                    # 仪器化测试（录音/Opus 编解码探针）
├── screenshots/                            # 运行截图
├── gradle/                                 # Gradle wrapper 与版本目录（libs.versions.toml）
├── AGENTS.md                               # AI 代理上下文文档
├── DISCLAIMER.md                           # 免责声明
└── README.md
```

## 技术栈

| 模块 | 技术 |
| --- | --- |
| Android 客户端 | Kotlin 2.2、Android 原生 View 体系（AppCompat + Material）、OkHttp 4.12（WebSocket）、Gson、Kotlin Coroutines、NDK + CMake + libopus（JNI） |
| Android 构建 | Gradle（AGP 9.2.1）、Version Catalog、minSdk 21 / targetSdk 27 / compileSdk 36 |

> 说明：Android 端采用原生 View 体系而非 Jetpack Compose，以保持对 Android 5.0（API 21）的兼容性；音频编解码走 NDK 直调 libopus，避免引入大型第三方库。

## 快速开始

### 环境要求

- **Android 端**：Android Studio（Ladybug 及以上）、JDK 17+、Android SDK（compileSdk 36）、CMake 与 NDK（用于编译 opus_jni）

### Android 端编译运行

1. 克隆项目并用 Android Studio 打开：

   ```bash
   git clone https://github.com/GitHubNull/HelloXiaoZhi.git
   cd HelloXiaoZhi
   ```

2. 等待 Gradle 同步完成（首次会下载依赖与 NDK 工具链）。

3. 连接 Android 手机（或模拟器），点击 **Run ▶** 编译安装。

4. 首次启动默认直连官方服务器：应用会自动发起 OTA 注册并弹出激活对话框，显示 6 位验证码；到 [xiaozhi.me](https://xiaozhi.me) 控制台添加设备并输入验证码完成激活，然后即可开始对话。

5. 如需连接自建服务器：进入 **设置**，关闭「官方服务器直连」或修改 WebSocket 地址（如 `ws://192.168.x.x:5000`），保存后返回即可。

> 编译脚本：`gradlew assembleDebug`（Windows 下为 `gradlew.bat assembleDebug`），产物在 `app/build/outputs/apk/debug/`。

## 架构设计

### 系统总览

```
┌────────────────────┐        WebSocket (wss://)        ┌─────────────────────┐
│   HelloXiaoZhi     │ ◄──────────────────────────────► │                     │
│  （Android 客户端） │        Opus 音频帧 + JSON 消息     │  小智官方服务器      │
│                    │                                   │  (api.tenclass.net) │
└──────┬──────┬──────┘                                   └─────────────────────┘
       │      │
       │      └── OTA 注册（HTTP POST）──► 验证码激活 → 建连
       │
       │    ┌──────────────────────────────┐
       └──► │  自建代理服务器（可选）         │
           │  WS 代理 + WAV 下发            │
           └──────────────────────────────┘
```

客户端连接流程：`连接 →（官方模式）OTA 注册 → 未激活则展示验证码并每 5 秒轮询 → 激活完成 → 新建 WebSocket → 发送 hello 握手（携带 Device-Id / Client-Id / Protocol-Version / Authorization 请求头）→ 进入对话状态`。

### 语音通话状态机（ChatStateMachine）

对话采用**状态驱动**设计：

```
                    电平 > 0.04（开始说话）
        ┌─────────────── 发送 listen start ───────────────┐
        │                                                 ▼
     ┌──────┐                                      ┌──────────────┐
     │ IDLE │                                      │ USER_SPEAKING │
     └──────┘                                      └──────┬───────┘
        ▲                                                 │ 静音 ≥ 1000ms
        │                                                 │ 发送 listen stop
        │  播放队列播空（≥500ms）                            ▼
        │                                    ┌──────────────┐
        └──────────── 回到 IDLE ◄─────────── │ AI_SPEAKING  │
                                            └──────────────┘
                                                 │
                                  用户打断（电平 > 0.1）
                                  发送 abort → USER_SPEAKING
```

- **IDLE**：静默等待；检测到说话电平（> 0.04）进入 `USER_SPEAKING`
- **USER_SPEAKING**：持续上行 Opus 音频帧；静音满 1 秒进入 `AI_SPEAKING`
- **AI_SPEAKING**：播放服务器 TTS 音频；用户电平 > 0.1 视为打断，发送 `abort` 并回到 `USER_SPEAKING`

### 音频数据链路

```
上行：麦克风 → AudioRecord（16kHz/44.1kHz 降级重采样）→ 60ms 成帧（960 采样）
      → RMS 电平检测 → 状态机决策 → libopus 编码 → WebSocket 二进制帧
下行：WebSocket 二进制帧 → 魔数分流（RIFF=WAV / 其它=Opus）
      → libopus 解码（采样率按 hello 响应动态调整）→ 播放队列 → AudioTrack
```

### WebSocket 消息协议

- 文本帧：JSON 消息，类型包括 `hello`（握手）、`listen`（start/stop/detect）、`abort`、`stt`、`llm`、`tts`
- 二进制帧：Opus 音频（官方直连）或 RIFF WAV（自定义代理下发）
- 断线后 3 秒自动重连；所有 UI 回调均投递到主线程

## 常见问题（FAQ）

**Q1：一直弹激活对话框，验证码是干什么的？**

直连官方服务器时，首次使用的设备需要通过 OTA 注册获得 6 位验证码，然后到 [xiaozhi.me](https://xiaozhi.me) 控制台「添加设备」并输入验证码完成激活。激活完成后 App 会自动建连；官方协议要求激活后必须**新建** WebSocket 连接才生效（代码中已处理）。

**Q2：说话没反应 / 语音电平恒为 0？**

- 检查麦克风权限是否授予（首次进入语音通话会请求 RECORD_AUDIO 权限）。
- 部分设备（如一加/OPPO）在通话模式下会把麦克风输入当回声消除，本项目已改用原始 MIC 采集源规避。
- 可在 Logcat 中过滤 `AudioRecorder` 观察 `record frame #N level=...` 日志，正常说话时电平应 > 0.04。

**Q3：提示「当前设备不支持录音（缺少 16000Hz 采样率支持）」？**

设备不支持 16kHz 采集时会自动降级到 44.1kHz 采集并线性重采样到 16kHz；若连 44.1kHz 都不支持（极少数老设备），会提示此错误。

**Q4：如何连接自建服务器？**

在设置中关闭「官方服务器直连」（或直接把 WebSocket 地址改为 `ws://<IP>:5000`），保存即可。此时走自定义模式，跳过 OTA 激活直接建连。

**Q5：支持哪些 Android 版本？**

minSdk 21（Android 5.0）起，兼容armeabi-v7a / arm64-v8a / x86 / x86_64 四种 ABI。libopus 通过 NDK 本地编译，不依赖在线下载。

## 许可证与声明

本项目基于 MIT 许可证开源，详见 [LICENSE](LICENSE)。使用本项目前请阅读 [DISCLAIMER.md](DISCLAIMER.md) 中的免责声明。

感谢 [xiaozhi](https://github.com/78/xiaozhi)、[xiaozhi-esp32](https://github.com/78/xiaozhi-esp32)、[xiaozhi-webui](https://github.com/kalicyh/xiaozhi-webui) 等开源项目的协议参考与实现启发。
