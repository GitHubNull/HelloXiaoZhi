# agents.md — HelloXiaoZhi AI 代理上下文文档

> 本文档面向 AI 编程代理与后续开发者，描述 HelloXiaoZhi 的核心业务逻辑、状态机设计、音频处理流程与 WebSocket 通信机制。所有符号名可直接在代码库中定位。

## 1. 项目概览

HelloXiaoZhi 是 Android 端「小智」AI 语音助手客户端，协议参考 [xiaozhi-esp32](https://github.com/78/xiaozhi-esp32)，逻辑逐行移植自 [xiaozhi-webui](https://github.com/kalicyh/xiaozhi-webui)（仓库内 `ref/xiaozhi-webui-master/` 为参考实现副本）。

- **Android 端**：`app/src/main/java/org/oxff/helloxiaozhi/`（Kotlin，原生 View 体系，非 Compose）
- **参考 Web 端**：`ref/xiaozhi-webui-master/src/`（Vue3 + TS）与 `ref/xiaozhi-webui-master/backend/`（Python FastAPI 代理）
- **关键设计原则**：Android 端代码注释中大量标注了「对应 Web 端 Xxx.ts / ref 后端 xxx.py」的对应关系，修改时务必保持行为对齐。

## 2. 模块索引（符号名 → 职责）

| 模块 | 关键符号 | 职责 |
| --- | --- | --- |
| 配置 | `AppConfig`（config/） | SharedPreferences 持久化：wsUrl / otaUrl / token / clientId / deviceId；`isOfficialMode()` 判定官方直连；`clear()` 供重置 |
| 数据层 | `BotRepository`（data/） | 机器人/会话/未读/唤醒目标的 JSON 文件持久化（debounce 落盘、原子写、损坏回落 seed） |
| 编排 | `XiaoZhiController`（controller/） | 核心胶水层：组装 WebSocket、激活流程、录音/播放、状态机；按机器人切换设备身份；全部 UI 回调在主线程 |
| WebSocket | `XiaoZhiWebSocket`（net/） | OkHttp WebSocket 客户端：握手头、hello、自动重连（3s）；`connect(deviceId)` 钉住身份 |
| OTA | `OtaClient`（net/）、`ActivationFlow`（activation/） | HTTP 设备注册；验证码激活轮询（5s）；`probeOnce` 为任意 MAC 单次取码 |
| 状态机 | `ChatStateMachine`（chat/） | 语音通话状态机（详见 §3）；`onStateChanged` 驱动通话页动画 |
| 消息模型 | `Messages.kt`、`ChatModels.kt`（chat/） | 上行/下行 JSON 消息数据类；ChatState/ChatEvent/ChatRole/ConnectionStatus（含 CONNECTING） |
| 音频采集 | `AudioRecorderManager`（audio/） | AudioRecord 采集、成帧（960 采样/60ms）、RMS 电平、44.1k→16k 重采样兜底；上行增益与 VAD 解耦 |
| 上行增强 | `MicEnhancer`（audio/） | 帧级 AGC（轻声放大 +24dB 上限/大声衰减防削波）+ 噪声门（防底噪误触发服务器端 VAD） |
| 音频播放 | `AudioPlayer`（audio/） | AudioTrack 播放队列、打断清空、队列播空回调；`playbackGain` 播放增益 |
| Opus 编解码 | `OpusCodec`（audio/）→ `app/src/main/cpp/opus_jni.c` | libopus JNI 封装；编码 16k/单声道/60ms；解码采样率动态 |
| WAV 解析 | `WavParser`（audio/） | RIFF 魔数检测与解析（WebUI 代理下发的 WAV 分流） |
| 工具 | `AudioMath`、`DeviceInfoProvider`、`Executors.kt`、`MacGenerator`、`TimeFormat`（util/） | 电平计算、设备 ID（MAC 格式）生成、主线程执行器/静音调度器、MAC 生成校验、时间展示 |
| UI 外壳 | `MainActivity`（ui/） | 三 Tab 外壳：导航栏 + 聊天/通讯录/设置 + 对话详情滑入层 + 模态框/Toast 宿主 |
| 页面控制器 | `ui/page/{ChatPage,ChatDetail,ContactsPage,SettingsPage}Controller` | 各 Tab 的渲染与交互（普通 Kotlin 类，非 Fragment） |
| 自定义 View | `ui/view/{StarfieldCallView,SlideInContainer,XzSwitch,WaveBarsView,ToastHost,ModalHost,Pressable,BubbleDrawables,AvatarPalette}` | 星河通话动画、滑入容器、开关、声浪、Toast、模态框、按压反馈、气泡/头像背景 |
| 通话页 | `VoiceCallActivity`（ui/） | 二进制星河 + 计时 + 历史 + 增益控制 |

## 3. 语音通话状态机（ChatStateMachine）

### 3.1 状态与常量（服务器端 VAD 驱动，对齐参考 APP auto 模式）

```kotlin
enum class ChatState { IDLE, USER_SPEAKING, AI_SPEAKING }
```

无客户端 VAD 阈值：`THRESHOLD_SPEAKING` / `THRESHOLD_INTERRUPT` / `SILENCE_MS` 均已移除，用户开口/停说由服务器端 VAD 通过 `stt` / `tts` 消息通知客户端。关键延迟常量在 `XiaoZhiController.Companion`：`TTS_PLAY_DELAY_MS=300L`（AI 开始播放延迟，避免服务器端 VAD 把 TTS 开头误判为用户语音）、`TTS_STOP_GRACE_MS=800L`（tts stop 后等尾音帧的宽限期）。

状态机的 `onStateChanged: ((ChatState) -> Unit)?` 钩子在 `transition()` 末尾触发；通话页的星河双球动画由它驱动（`XiaoZhiController` 转发给 `onChatStateChanged`）。

### 3.2 状态转换（由服务器消息驱动）

- **IDLE**：所有音频帧直接上行（服务器端 VAD 检测），收到服务器 `stt` → `USER_SPEAKING`
- **USER_SPEAKING**：每帧上行 Opus + 电平驱动声浪 UI；服务器 `tts start` → `AI_SPEAKING`
- **AI_SPEAKING**：完全不上行（`XiaoZhiController.isAiPlaying` 门控，对齐参考 APP `!o.f215p`），避免 TTS 泄漏污染服务器端 VAD；`tts stop` 宽限期后队列已播空 → `IDLE`

### 3.3 消息副作用与 listen 生命周期（真机回归教训）

- listen start/stop **不由状态迁移发送**：`startVoiceCall()` 主动发 `ListenMessage.start`（mode=auto），`stopVoiceCall()` 发 `ListenMessage.stop`；断线重连后 `handleHello` 检测到通话中（recorder 非空）用新 session 重发 listen start。**每轮回复播完后必须重发 listen start**（`ChatEvent.AI_STOP_SPEAKING` 且通话中）：官方服务器在 tts stop 后不会自动继续监听，不重发则只有第一轮被识别，后续语音要等挂断时的 listen stop 才被一次性识别（真机实测）。若等收到 `stt` 才发 listen start 会形成死锁；中途重发会重置服务器监听；`version=1 + response_mode="manual"`（参考 APP 对接的第三方服务器协议）与官方/自建代理不兼容，已回退 `version=3`
- 进入 `USER_SPEAKING`：触发 `ChatEvent.USER_START_SPEAKING`（→ `AudioPlayer.pausePlayback()` 清空队列）
- 离开 `USER_SPEAKING`：`ChatEvent.USER_STOP_SPEAKING`
- 进入 `AI_SPEAKING`：触发 `ChatEvent.AI_START_SPEAKING`（→ 延迟 300ms `AudioPlayer.resumePlayback()`）
- 离开 `AI_SPEAKING`：触发 `ChatEvent.AI_STOP_SPEAKING`
- 状态机外部回退：`AudioPlayer.onQueueEmpty`（队列播空 ≥500ms）或 `tts stop` 宽限期到期 → 若处于 `AI_SPEAKING` 则 `setState(IDLE)`

### 3.4 线程模型

`handleAudioLevel` / `setState` 均通过 `UiExecutor`（主线程 Handler）串行化；`SilenceScheduler` 基于 Handler postDelayed。**禁止**在非主线程直接改状态。单元测试中 `logger` 默认为 no-op（避免 android.util.Log 在 JVM 环境抛异常）。

## 4. 音频处理流程

### 4.1 上行（录音 → 服务器）

```
AudioRecorderManager.recordLoop()
  → AudioRecord（优先 16kHz；getMinBufferSize 不支持则 44.1kHz + LinearResampler 转 16kHz）
  → 每次 read 40ms 数据，切分为 60ms 帧（OpusCodec.FRAME_SIZE = 960）
  → AudioMath.rmsLevel(frame) 计算电平（仅驱动声浪 UI，无客户端 VAD；用原始帧）
  → MicEnhancer.process(frame)：帧级语音增强（仅作用于上行帧）
      AGC：轻声帧放大逼近目标峰值电平 0.3（上限 +24dB），大声帧衰减防削波（下限 -12dB）
      噪声门：低于「底噪 × 2」的帧衰减到 0.1 倍，防 AGC 放大背景噪声误触发服务器端 VAD
  → applyMicGain：叠加用户增益（通话页滑块 ±12dB）
  → isAiPlaying 门控（AI 播放时完全不上行）
  → ChatStateMachine.handleAudioLevel(level, frame)
      → IDLE / USER_SPEAKING 时回调 sendAudioData → OpusCodec.encode → ws.sendOpus（二进制帧）
```

关键细节：
- 音频源优先 `VOICE_COMMUNICATION`（内置 AEC/NS），若电平连续 150 帧恒为 0 自动降级 `MIC`，降级后手动挂 `AcousticEchoCanceler` + `NoiseSuppressor`（失败静默降级）
- `MicEnhancer`（audio/）：纯 Kotlin 无 Android 依赖；底噪由首帧引导初始化，只有低于「底噪 × 4」的帧参与底噪跟踪（持续轻声不会被误学成底噪）；诊断字段 `lastGain`/`currentNoiseFloor`/`currentEstPeak` 每 100 帧随电平日志输出（`enh[...]` 段）

### 4.2 下行（服务器 → 播放）

```
XiaoZhiWebSocket.onMessage(ByteString) → listener.onAudioFrame
  → XiaoZhiController.handleAudioFrame（OkHttp 回调线程执行）
      → WavParser.isWav(data)？
          RIFF 魔数：WavParser.parse → PCM（WebUI 代理下发，采样率可能变化，同步 player.setSampleRate）
          否则：OpusCodec.decode(data, sampleRate*60/1000)（16kHz=960，24kHz=1440）
  → AudioPlayer.enqueue(pcm)
  → 若状态为 IDLE → setState(AI_SPEAKING)
```

播放器为单线程消费 `ConcurrentLinkedQueue`；`pausePlayback()` 清空队列并 pause+flush AudioTrack，`resumePlayback()` 恢复；懒创建 AudioTrack（MODE_STREAM，USAGE_MEDIA/CONTENT_TYPE_SPEECH）。

## 5. WebSocket 通信机制

### 5.1 握手（XiaoZhiWebSocket.connect）

请求头：`Device-Id`（MAC 格式）、`Client-Id`（UUID，首次生成持久化）、`Protocol-Version: 1`，token 开启时附加 `Authorization: Bearer <token>`。连接建立（onOpen）后立即发送 `HelloMessage`（type=hello, version=3, transport=websocket, audio_params=opus/16k/1ch/60ms 帧）。

### 5.2 消息类型（Messages.kt，逐字段对齐 ref types/message.ts）

**上行**：
- `HelloMessage`：握手（音频参数声明）
- `ListenMessage`：`state=start/stop`，mode=auto；`DetectMessage`：state=detect，文字输入（source=text）
- `AbortMessage`：打断 TTS，携带 session_id

**下行**（XiaoZhiController.handleTextMessage 分发）：
- `hello` → 记录 session_id，按服务器 audio_params.sample_rate 重建解码器（handleHello）
- `stt` → 用户语音识别文本（ChatRole.USER 追加）
- `llm` → 模型回复文本（ChatRole.AI 追加）
- `tts` → 状态机：`start` 时若 IDLE 则进入 AI_SPEAKING；`sentence_start` 追加文本（以 `%` 开头的控制文本不展示）

### 5.3 连接生命周期

- `connect(deviceId)`：已连接时 no-op；`disconnect()`：置 autoReconnect=false 并 close(1000)
- 断线（onClosed/onFailure）→ 3 秒后自动重连（`RECONNECT_DELAY_MS=3000`），重连复用 connect 时钉住的 deviceId
- 连接状态枚举 `ConnectionStatus`：CONNECTED / CONNECTING / DISCONNECTED / ERROR
- 切换机器人：`XiaoZhiController.switchActiveBot(botId)` 按「取消激活轮询 → 断开 → 改身份 → 重连」的顺序执行，避免排队中的重连任务用错身份

## 6. 激活与连接流程（官方直连模式）

1. `XiaoZhiController.ensureConnected()`：官方模式（`AppConfig.isOfficialMode()`）→ `ActivationFlow.ensureActivated(identity, listener)`，identity 取当前激活机器人的 MAC
2. `OtaClient.register(otaUrl, deviceId, clientId, localIp)` POST OTA 注册（payload 逐字段对齐 websocket_proxy.py，模拟 ESP32 固件信息）
3. 响应含 `activation.code`（6 位验证码）→ `onActivationCodeRequired` 弹框展示，每 5s 轮询（`POLL_INTERVAL_MS=5000`），用户可点「我已添加设备」立即检查（`requestCheckNow()`）
4. `activation` 字段消失 → `onActivated` → **必须新建 WebSocket 连接**（官方协议要求）
5. 自定义模式（非官方 URL）跳过激活直接 `ws.connect(bot.mac)`
6. 「获取该 MAC 的激活码」（添加机器人模态框）调用 `ActivationFlow.probeOnce(identity)`：单次注册、不轮询、不改全局配置

## 7. 与参考实现的对应关系

| Android 端 | 参考实现 |
| --- | --- |
| `ChatStateMachine` | `ref/.../src/services/ChatStateManager.ts` |
| `XiaoZhiWebSocket` | `ref/.../src/services/WebSocketManager.ts` + `backend/app/proxy/websocket_proxy.py`（服务器侧握手） |
| `AudioRecorderManager` | Web 端 AudioWorklet + `backend/app/utils/audio.py` |
| `AudioPlayer` | Web 端 AudioService（`ref/.../src/services/AudioManager.ts`） |
| `OtaClient` | `backend/app/proxy/websocket_proxy.py` 的 `_update_ota_address()` |
| `ActivationFlow` | 官方固件 `kDeviceStateActivating → kDeviceStateIdle` 阶段 |
| `XiaoZhiController` | Web 端 `App.vue` 的组装逻辑 + 后端代理职责 |

## 8. 测试与构建

- **单元测试**（`app/src/test/`）：`ChatStateMachineTest`、`MessagesTest`、`WavParserTest`、`OtaClientTest`、`AudioMathTest`、`BotRepositoryTest`、`MacGeneratorTest`、`TimeFormatTest`、`AvatarPaletteTest`、`MicEnhancerTest` 及 `asr/` 测试体系。运行：`gradlew testDebugUnitTest`
- **仪器化测试**（`app/src/androidTest/`）：`AudioRecordProbeInstrumentedTest`、`OpusCodecInstrumentedTest`。运行需真机/模拟器
- **构建**：`gradlew assembleDebug`；原生库由 CMake 编译（`app/src/main/cpp/CMakeLists.txt`），ABI：arm64-v8a / armeabi-v7a / x86_64 / x86
- **构建约束**：OkHttp 锁定 4.12.0（最后一个支持 API 21 的版本，勿升级）；targetSdk 27 且 lint 禁用 `ExpiredTargetSdkVersion`（兼容旧设备）；Java/Kotlin target 11
- **命令行构建必须显式指定 JDK 21**：裸跑会因工具链自动探测选中 Qoder redhat.java 扩展自带的 JRE 21（无 jlink）而失败（JdkImageTransform 报 `jlink.exe does not exist`）。必须同时：① `$env:JAVA_HOME='D:\dev_env\java\jdk\21'`（钉住守护进程 JVM，仅传 -D 参数不够）；② 若已有守护进程先 `gradlew --stop`；③ 追加 `-Dorg.gradle.java.installations.auto-detect=false -Dorg.gradle.java.installations.paths='D:\dev_env\java\jdk\21'`

## 9. 常见改动提示

- 改动协议消息字段：同步修改 `Messages.kt` 与 `ref/xiaozhi-webui-master/src/types/message.ts`
- 改动状态机逻辑：必须更新 `ChatStateMachineTest` 中对应的转换用例
- 改动机器人/会话数据模型或持久化格式：必须更新 `BotRepositoryTest`；`AppData.version` 递增以触发旧档重建
- 改动 UI 结构：保持三 Tab 外壳的层叠顺序（Tab 内容 < Tab 栏 < 对话详情 < 模态框 < Toast），且 controller 回调只在 `MainActivity` 单点绑定再分发，不要在页面控制器里直接绑定
- 改动音频参数（采样率/帧长）：`AudioParams`（hello）、`OpusCodec.FRAME_SIZE`、`AudioRecorderManager` 成帧逻辑需同步
- 改动上行增强参数（目标电平/增益上下限/噪声门阈值）：必须更新 `MicEnhancerTest` 对应用例；调参前注意「底噪跟踪只吃低于底噪×4 的帧」「电平用原始帧与增强器解耦」两条不变式
- 真机排障：Logcat 过滤 `XiaoZhiController`（连接/消息）、`AudioRecorder`（电平帧）、`XiaoZhiWebSocket`（WS 生命周期）、`[SM]` 前缀（状态迁移）
