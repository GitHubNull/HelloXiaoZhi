# AGENTS.md — HelloXiaoZhi AI 代理上下文文档

> 本文档面向 AI 编程代理与后续开发者，描述 HelloXiaoZhi 的核心业务逻辑、状态机设计、音频处理流程与 WebSocket 通信机制。所有符号名可直接在代码库中定位。

## 1. 项目概览

HelloXiaoZhi 是 Android 端「小智」AI 语音助手客户端，协议参考 [xiaozhi-esp32](https://github.com/78/xiaozhi-esp32)，逻辑移植自 [xiaozhi-webui](https://github.com/kalicyh/xiaozhi-webui) 开源项目。

- **Android 端**：`app/src/main/java/org/oxff/helloxiaozhi/`（Kotlin，原生 View 体系，非 Compose）
- **关键设计原则**：核心逻辑（状态机、消息协议、音频链路）改动时注意保持协议行为一致。

## 2. 模块索引（符号名 → 职责）

| 模块 | 关键符号 | 职责 |
| --- | --- | --- |
| 配置 | `AppConfig`（config/） | SharedPreferences 持久化：wsUrl / otaUrl / token / clientId / deviceId；`isOfficialMode()` 判定官方直连；`clear()` 供重置 |
| 数据层 | `BotRepository`（data/） | 机器人/会话/未读/唤醒目标的 JSON 文件持久化（debounce 落盘、原子写、损坏回落 seed） |
| 编排 | `XiaoZhiController`（controller/） | 核心胶水层：组装 WebSocket、激活流程、录音/播放、状态机；按机器人切换设备身份；全部 UI 回调在主线程 |
| 音频管道 | `AudioPipeline`（controller/） | 从 `XiaoZhiController` 拆出：录音与播放生命周期、Opus 编解码、上/下行帧处理、本地音乐与服务器回复的门控（详见 §3.4/§4） |
| 消息分发 | `MessageDispatcher`（controller/） | 从 `XiaoZhiController` 拆出：下行文本消息（hello/stt/llm/tts）解析与分发、聊天落库、音乐指令拦截与唤醒词剥离（详见 §3.4/§5.2） |
| WebSocket | `XiaoZhiWebSocket`（net/） | OkHttp WebSocket 客户端：握手头、hello、自动重连（3s）；`connect(deviceId)` 钉住身份 |
| OTA | `OtaClient`（net/）、`ActivationFlow`（activation/） | HTTP 设备注册；验证码激活轮询（5s）；`probeOnce` 为任意 MAC 单次取码 |
| 状态机 | `ChatStateMachine`（chat/） | 语音通话状态机（详见 §3）；`onStateChanged` 驱动通话页动画 |
| 消息模型 | `Messages.kt`、`ChatModels.kt`（chat/） | 上行/下行 JSON 消息数据类；ChatState/ChatEvent/ChatRole/ConnectionStatus（含 CONNECTING） |
| 音频采集 | `AudioRecorderManager`（audio/） | AudioRecord 采集、成帧（960 采样/60ms）、RMS 电平、44.1k→16k 重采样兜底；上行增益与 VAD 解耦 |
| 上行增强 | `MicEnhancer`（audio/） | 帧级 AGC（轻声放大 +24dB 上限/大声衰减防削波）+ 噪声门（防底噪误触发服务器端 VAD） |
| 音频播放 | `AudioPlayer`（audio/） | AudioTrack 播放队列、打断清空、队列播空回调；`playbackGain` 播放增益 |
| Opus 编解码 | `OpusCodec`（audio/）→ `app/src/main/cpp/opus_jni.c` | libopus JNI 封装；编码 16k/单声道/60ms；解码采样率动态 |
| WAV 解析 | `WavParser`（audio/） | RIFF 魔数检测与解析（自定义代理下发的 WAV 分流） |
| 工具 | `AudioMath`、`DeviceInfoProvider`、`Executors.kt`、`MacGenerator`、`TimeFormat`（util/） | 电平计算、设备 ID（MAC 格式）生成、主线程执行器/静音调度器、MAC 生成校验、时间展示 |
| UI 外壳 | `MainActivity`（ui/） | 三 Tab 外壳：导航栏 + 聊天/通讯录/设置 + 对话详情滑入层 + 模态框/Toast 宿主 |
| 页面控制器 | `ui/page/{ChatPage,ChatDetail,ContactsPage,SettingsPage}Controller` | 各 Tab 的渲染与交互（普通 Kotlin 类，非 Fragment） |
| 自定义 View | `ui/view/{StarfieldCallView,SlideInContainer,XzSwitch,WaveBarsView,ToastHost,ModalHost,Pressable,BubbleDrawables,AvatarPalette}` | 星河通话动画、滑入容器、开关、声浪、Toast、模态框、按压反馈、气泡/头像背景 |
| 通话页 | `VoiceCallActivity`（ui/） | 二进制星河 + 计时 + 历史 + 增益控制 |
| 音乐播放 | `MusicPlayer`、`MusicLibrary`（music/） | MediaPlayer 播放队列与播放状态；本地曲库（Room 持久化），按标题/歌手/专辑/类型检索 |
| 音乐指令 | `MusicActionMapper`、`MusicKeywordNormalizer`（music/） | STT 文本 → 音乐控制的关键词兜底路径；口语长句归一化成候选关键词后逐个反查曲库 |
| 机器人动作 | `RobotActionRegistry`、`McpActionHandler`（robot/） | MCP `tools/list` 与 `tools/call` 分发；`self.robot.*` / `self.music.*` 动作注册与执行 |

## 3. 语音通话状态机（ChatStateMachine）

### 3.1 状态与常量（服务器端 VAD 驱动 + 客户端本地打断检测）

```kotlin
enum class ChatState { IDLE, USER_SPEAKING, AI_SPEAKING }
```

用户开口/停说由服务器端 VAD 通过 `stt` / `tts` 消息通知客户端，**但打断（barge-in）必须由客户端本地电平检测**：AI 播放期间不上行音频，服务器端 VAD 收不到任何声音而永远不会下发 `stt`，任何「收到 stt 再打断」的逻辑构成死锁（真机实测：AI 连续输出 TTS 两分钟内零条 stt）。

`ChatStateMachine.Companion` 打断常量：`THRESHOLD_INTERRUPT=0.1f`、`REQUIRED_INTERRUPT_FRAMES=3`（约 180ms 防抖）、`PRE_ROLL_INTERRUPT_FRAMES=16`（约 960ms 预触发环形缓冲）。取值依据：`VOICE_COMMUNICATION` + 硬件 AEC 下，AI 播放期间麦克风残留回声电平实测仅 0.011~0.06，远低于 0.1，故不会被 TTS 回声误触发。

`THRESHOLD_SPEAKING` / `SILENCE_MS` 仍保持移除（开口/停说交由服务器端 VAD）。关键延迟常量在 `AudioPipeline.Companion`：`TTS_PLAY_DELAY_MS=300L`（AI 开始播放延迟）、`TTS_STOP_GRACE_MS=200L`（tts stop 后等尾音帧的宽限期）、`UPLINK_READY_DELAY_MS=1500L`（进通话后给 listen start 留出到达服务器并激活 VAD 的窗口，窗口内上行帧丢弃）。

状态机的 `onStateChanged: ((ChatState) -> Unit)?` 钩子在 `transition()` 末尾触发；通话页的星河双球动画由它驱动（`XiaoZhiController` 转发给 `onChatStateChanged`）。

### 3.2 状态转换（服务器消息 + 本地电平共同驱动）

- **IDLE**：所有音频帧直接上行（服务器端 VAD 检测），收到服务器 `stt` → `USER_SPEAKING`
- **USER_SPEAKING**：每帧上行 Opus + 电平驱动声浪 UI；服务器 `tts start` → `AI_SPEAKING`
- **AI_SPEAKING**：不上行（避免 TTS 泄漏污染服务器端 VAD），但每帧仍送状态机：压入 `preRollBuffer` + 做本地打断检测。电平 > `THRESHOLD_INTERRUPT` 连续 3 帧 → 发 `AbortMessage` + 迁移 `USER_SPEAKING`；`tts stop` 宽限期后队列已播空 → `IDLE`
- **打断时序（真机验证，不得调换）**：`AbortMessage` → `AI_STOP_SPEAKING`（外部据此重发 listen start）→ `flushPreRoll()` 补发句首帧 → `USER_START_SPEAKING`。句首补发必须在 listen start 之后：服务器必须先收到 listen start 才会处理上行音频，先补发会被丢弃

### 3.3 消息副作用与 listen 生命周期（真机回归教训）

- listen start/stop **不由状态迁移发送**：`startVoiceCall()` 主动发 `ListenMessage.start`（mode=auto），`stopVoiceCall()` 发 `ListenMessage.stop`；断线重连后 `handleHello` 检测到通话中（recorder 非空）用新 session 重发 listen start。**每轮回复播完后必须重发 listen start**（`ChatEvent.AI_STOP_SPEAKING` 且通话中）：官方服务器在 tts stop 后不会自动继续监听，不重发则只有第一轮被识别，后续语音要等挂断时的 listen stop 才被一次性识别（真机实测）。若等收到 `stt` 才发 listen start 会形成死锁；中途重发会重置服务器监听；`version=1 + response_mode="manual"`（第三方服务器协议）与官方/自建代理不兼容，已回退 `version=3`
- 进入 `USER_SPEAKING`：触发 `ChatEvent.USER_START_SPEAKING`（→ `pausePlayback()` 清空队列 + `clearAiPlaying()` 复位播放标记 + `messageDispatcher.clearPendingFarewell()` 清除残留结束语标志）。清结束语是必要的：打断后队列已被清空、`onQueueEmpty` 不再触发（`pausePlayback` 置 `playing=false`，播放循环走等待分支），残留标志会在后续任意一次队列播空时误触发自动挂断
- 离开 `USER_SPEAKING`：`ChatEvent.USER_STOP_SPEAKING`
- 进入 `AI_SPEAKING`：触发 `ChatEvent.AI_START_SPEAKING`（→ 延迟 300ms `AudioPlayer.resumePlayback()`）
- 离开 `AI_SPEAKING`：触发 `ChatEvent.AI_STOP_SPEAKING`（→ 通话中且已连接时重发 listen start）
- 状态机外部回退：`AudioPlayer.onQueueEmpty`（队列播空 ≥500ms）或 `tts stop` 宽限期到期 → 若处于 `AI_SPEAKING` 则 `setState(IDLE)`
- **本地音乐播放（`isLocalMusicPlaying`）必须让状态机脱离 `AI_SPEAKING`**：`MusicPlayer.onMusicStart` → `AudioPipeline.enterLocalMusicMode()`（复位 `isAiPlaying` + 强制 `setState(IDLE)`）。两个必要原因：① `MusicPlayer` 用 `MediaPlayer` 播放，其声音**不在 `AudioTrack` 的 AEC 参考信号内**，会被麦克风完整录入且电平远超打断阈值，若停在 `AI_SPEAKING` 会被音乐声持续误触发打断；② 必须走 IDLE 分支持续上行，服务器端 VAD 才能识别唤醒词打断指令
- **音乐播放期间的打断靠 STT 文本唤醒词前缀匹配**（`MessageDispatcher.stripWakeWordPrefix`，唤醒词 = `SherpaOnnxWakeWordEngine.DEFAULT_KEYWORD` + “小智小智”别名）：非唤醒词开头 → 直接忽略（不落库、不迁移状态、音乐照播）；以唤醒词开头 → 剥离前缀后按「音乐指令 / 对话」分类处理（详见 §3.4）。不能用本地 KWS：通话中 `WakeWordService` 已 `pauseDetection()` 释放麦克风

### 3.4 音乐指令的本地拦截与服务器回复抑制

音乐控制指令（停止/暂停/切歌/点歌）属**纯设备操作**，不得进入聊天上下文。三层机制缺一不可（真机实测：只扩充关键词表仍会让 AI 接着 “别唱了” 闲聊）：

1. **指令分类 + 延迟兜底**（`MusicActionMapper.isControlCommand` / `isPlayRequest`，只判定不执行）：`MessageDispatcher.handleSttDuringMusic` 据此分流。
   - **顺序不变式**：必须**先分类、再决定停不停音乐**。旧实现无条件先调 `onWakeWordInterrupt` 停音乐，“阿妹阿妹，下一首” 会先彻底终止播放、再执行已无意义的 `next()`，切歌失效
   - **拦截范围不变式**：「播放类」任何场景都进延迟兜底路径（听歌意图明确，即使无音乐也覆盖）；「控制类」**仅音乐播放中**拦截——无音乐时 “你别说话”/“先停下来” 是正常聊天，拦截会吞掉用户对话并误发 abort。由 `handleSttDuringMusic` 内的 `musicActive` 判定区分
   - **延迟兜底时序（真机实测驱动的取舍，不得调换）**：命中 → **先 `appendChat` 落库**（透传服务器 AI，不迁移状态、不停音乐）+ 启动 `MUSIC_COMMAND_DELAY_MS=800ms` 本地兜底定时器（`scheduleMusicFallback`）→ 服务器在窗口期内经 MCP `self.music.*` 调用则 `cancelPendingMusicCommand()` 取消本地执行（`handleMcp` 检测 `params.name` 前缀，非音乐工具不取消）→ 超时才本地 `processText` 执行并 `onLocalMusicHandled`（发 `AbortMessage` + 开抑制窗口）。立即本地执行会抢跑服务器 AI 的 MCP 路径；只落库不兜底则在服务器不理解指令时音乐毫无响应
   - 未命中 → `onWakeWordInterrupt` 停音乐后走正常聊天流程
2. **服务器回复抑制窗口**（`AudioPipeline.suppressServerReply`，`SERVER_REPLY_SUPPRESS_MS=12000L`）：停止类指令会让 `isLocalMusicPlaying` 立即复位，「音乐期间丢弃 TTS」那道门控随之失效，abort 在途的 `llm`/`tts` 文本与音频帧仍会播出。窗口内四处丢弃：`AudioPipeline.onTtsStart` / `scheduleAudioFrame`（音频）+ `MessageDispatcher.handleLlm` / `handleTts`（文本，经 `isReplySuppressedProvider`）。取值需覆盖两类：abort 在途（网络往返 + 服务器停止生成 + 在途帧排空）**+ 同批语音被服务器切出的 STT 尾巴所开启的一轮完整回复**（真机实测约 5~15s，过短会漏播后半句）。
3. **STT 免疫期**（`handleStt` 入口把 `isReplySuppressed()` 也纳入门控）：音乐已停后，同一句语音可能被服务器切出第二条 STT（真机实测「阿妹阿妹，别唱了」后紧跟「这。」）。若只判 `isLocalMusicPlaying`，音乐一停这条尾巴就绕过 `handleSttDuringMusic`、走 `handleSttText` 落库并清掉抑制，AI 随即围绕它接话。免疫期内无唤醒词的 STT 一律忽略（不落库、不迁移状态、不清抑制）；带唤醒词的真实对话照常。
4. **窗口清除**：用户以唤醒词开启新一轮真实对话（`handleSttText` 落库前）主动 `clearServerReplySuppress()`，避免下一轮 AI 回复被误丢；无唤醒词的尾巴不触碰抑制，与超时自然到期构成双保险

### 3.5 线程模型

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
  → AudioPipeline.onFrame：仅受 uplinkReadyAtMs 就绪窗口门控
      【不能用 isAiPlaying 门控】否则状态机收不到帧、无法做本地打断检测，
      且服务器也收不到音频而永不下发 stt，形成双重死锁
  → ChatStateMachine.handleAudioLevel(level, frame)
      → IDLE / USER_SPEAKING：回调 sendAudioData → OpusCodec.encode → ws.sendOpus（二进制帧）
      → AI_SPEAKING：不上行，压入 preRollBuffer + 本地打断检测（见 §3.1/§3.2）
```

关键细节：
- 音频源优先 `VOICE_COMMUNICATION`（内置 AEC/NS），若电平连续 150 帧恒为 0 自动降级 `MIC`，降级后手动挂 `AcousticEchoCanceler` + `NoiseSuppressor`（失败静默降级）
- `MicEnhancer`（audio/）：纯 Kotlin 无 Android 依赖；底噪由首帧引导初始化，只有低于「底噪 × 4」的帧参与底噪跟踪（持续轻声不会被误学成底噪）；诊断字段 `lastGain`/`currentNoiseFloor`/`currentEstPeak` 每 100 帧随电平日志输出（`enh[...]` 段）

### 4.2 下行（服务器 → 播放）

```
XiaoZhiWebSocket.onMessage(ByteString) → listener.onAudioFrame
  → XiaoZhiController.handleAudioFrame（OkHttp 回调线程执行）
      → WavParser.isWav(data)？
          RIFF 魔数：WavParser.parse → PCM（自定义代理下发，采样率可能变化，同步 player.setSampleRate）
          否则：OpusCodec.decode(data, sampleRate*60/1000)（16kHz=960，24kHz=1440）
  → AudioPlayer.enqueue(pcm)
  → 若状态为 IDLE → setState(AI_SPEAKING)
```

播放器为单线程消费 `ConcurrentLinkedQueue`；`pausePlayback()` 清空队列并 pause+flush AudioTrack，`resumePlayback()` 恢复；懒创建 AudioTrack（MODE_STREAM，USAGE_MEDIA/CONTENT_TYPE_SPEECH）。

## 5. WebSocket 通信机制

### 5.1 握手（XiaoZhiWebSocket.connect）

请求头：`Device-Id`（MAC 格式）、`Client-Id`（UUID，首次生成持久化）、`Protocol-Version: 1`，token 开启时附加 `Authorization: Bearer <token>`。连接建立（onOpen）后立即发送 `HelloMessage`（type=hello, version=3, transport=websocket, audio_params=opus/16k/1ch/60ms 帧）。

### 5.2 消息类型（Messages.kt）

**上行**：
- `HelloMessage`：握手（音频参数声明）
- `ListenMessage`：`state=start/stop`，mode=auto；`DetectMessage`：state=detect，文字输入（source=text）
- `AbortMessage`：打断 TTS，携带 session_id

**下行**（`MessageDispatcher.handleTextMessage` 分发）：
- `hello` → 记录 session_id，按服务器 audio_params.sample_rate 重建解码器（handleHello）
- `stt` → 用户语音识别文本。本地音乐播放中先经唤醒词门控与指令分类（详见 §3.4）；否则作为 `ChatRole.USER` 追加并驱动状态迁移（IDLE → USER_SPEAKING；AI_SPEAKING 下走 Barge-in）
- `llm` → 模型回复文本（ChatRole.AI 追加）；回复抑制窗口内直接丢弃（详见 §3.4）
- `tts` → 状态机：`start` 时若 IDLE 则进入 AI_SPEAKING；`sentence_start` 追加文本（以 `%` 开头的控制文本不展示）。两类均在抑制窗口内丢弃

### 5.3 连接生命周期

- `connect(deviceId)`：已连接时 no-op；`disconnect()`：置 autoReconnect=false 并 close(1000)
- 断线（onClosed/onFailure）→ 3 秒后自动重连（`RECONNECT_DELAY_MS=3000`），重连复用 connect 时钉住的 deviceId
- 连接状态枚举 `ConnectionStatus`：CONNECTED / CONNECTING / DISCONNECTED / ERROR
- 切换机器人：`XiaoZhiController.switchActiveBot(botId)` 按「取消激活轮询 → 断开 → 改身份 → 重连」的顺序执行，避免排队中的重连任务用错身份

## 6. 激活与连接流程（官方直连模式）

1. `XiaoZhiController.ensureConnected()`：官方模式（`AppConfig.isOfficialMode()`）→ `ActivationFlow.ensureActivated(identity, listener)`，identity 取当前激活机器人的 MAC
2. `OtaClient.register(otaUrl, deviceId, clientId, localIp)` POST OTA 注册（payload 模拟 ESP32 固件信息）
3. 响应含 `activation.code`（6 位验证码）→ `onActivationCodeRequired` 弹框展示，每 5s 轮询（`POLL_INTERVAL_MS=5000`），用户可点「我已添加设备」立即检查（`requestCheckNow()`）
4. `activation` 字段消失 → `onActivated` → **必须新建 WebSocket 连接**（官方协议要求）
5. 自定义模式（非官方 URL）跳过激活直接 `ws.connect(bot.mac)`
6. 「获取该 MAC 的激活码」（添加机器人模态框）调用 `ActivationFlow.probeOnce(identity)`：单次注册、不轮询、不改全局配置

## 7. 测试与构建

- **单元测试**（`app/src/test/`）：`ChatStateMachineTest`、`MessagesTest`、`WavParserTest`、`OtaClientTest`、`AudioMathTest`、`BotRepositoryTest`、`MacGeneratorTest`、`TimeFormatTest`、`AvatarPaletteTest`、`MicEnhancerTest`、`MusicKeywordNormalizerTest`、`MusicActionMapperTest`、`RobotActionRegistryTest`、`McpActionHandlerTest`、`MessageDispatcherTest` 及 `asr/` 测试体系。运行：`gradlew testDebugUnitTest`
- **仪器化测试**（`app/src/androidTest/`）：`AudioRecordProbeInstrumentedTest`、`OpusCodecInstrumentedTest`。运行需真机/模拟器
- **构建**：`gradlew assembleDebug`；原生库由 CMake 编译（`app/src/main/cpp/CMakeLists.txt`），ABI：arm64-v8a / armeabi-v7a / x86_64 / x86
- **构建约束**：OkHttp 锁定 4.12.0（最后一个支持 API 21 的版本，勿升级）；targetSdk 27 且 lint 禁用 `ExpiredTargetSdkVersion`（兼容旧设备）；Java/Kotlin target 11
- **命令行构建必须显式指定 JDK 21**：裸跑会因工具链自动探测选中 Qoder redhat.java 扩展自带的 JRE 21（无 jlink）而失败（JdkImageTransform 报 `jlink.exe does not exist`）。必须同时：① `$env:JAVA_HOME='D:\dev_env\java\jdk\21'`（钉住守护进程 JVM，仅传 -D 参数不够）；② 若已有守护进程先 `gradlew --stop`；③ 追加 `-Dorg.gradle.java.installations.auto-detect=false -Dorg.gradle.java.installations.paths='D:\dev_env\java\jdk\21'`

## 8. 常见改动提示

- 改动协议消息字段：同步修改 `Messages.kt`，并与服务器端实际协议保持兼容
- 改动状态机逻辑：必须更新 `ChatStateMachineTest` 中对应的转换用例
- 改动打断检测参数（`THRESHOLD_INTERRUPT` / `REQUIRED_INTERRUPT_FRAMES` / `PRE_ROLL_INTERRUPT_FRAMES`）：必须更新 `ChatStateMachineTest` 对应用例；调参前注意三条不变式：① AEC 后 AI 播放期间残留回声电平仅 0.011~0.06，阈值必须高于此区间否则 AI 会被自己的 TTS 回声打断；② 本地音乐（`MediaPlayer`）不在 AEC 参考信号内，播放期间必须让状态机脱离 `AI_SPEAKING`；③ 打断时句首补发必须在 listen start 之后（服务器先收 listen start 才处理上行音频）
- 改动机器人/会话数据模型或持久化格式：必须更新 `BotRepositoryTest`；`AppData.version` 递增以触发旧档重建
- 改动 UI 结构：保持三 Tab 外壳的层叠顺序（Tab 内容 < Tab 栏 < 对话详情 < 模态框 < Toast），且 controller 回调只在 `MainActivity` 单点绑定再分发，不要在页面控制器里直接绑定
- 改动音频参数（采样率/帧长）：`AudioParams`（hello）、`OpusCodec.FRAME_SIZE`、`AudioRecorderManager` 成帧逻辑需同步
- 改动上行增强参数（目标电平/增益上下限/噪声门阈值）：必须更新 `MicEnhancerTest` 对应用例；调参前注意「底噪跟踪只吃低于底噪×4 的帧」「电平用原始帧与增强器解耦」两条不变式
- 改动音乐关键词归一化（`MusicKeywordNormalizer`）：必须更新 `MusicKeywordNormalizerTest`；注意四条不变式：① 曲库 `search` 是「关键词须被标题/歌手包含」语义，候选越长越难命中，**禁止**退回「清洗出唯一关键词」的单串方案（真机实测口语长句必失配）；② 每片段须同时产出激进（剥填充词）与保守（保留）两个变体，否则以 "那个/这个" 开头的真实曲名会被误剥；③ 泛化词（"音乐"/"歌曲"）与纯填充词不得进候选，否则会误命中同名曲目；④ 含分隔标点的候选排在末尾兜底，覆盖曲名本身带逗号的情况
- 改动音乐指令匹配（`MusicActionMapper` 的 `PLAY_KEYWORDS` 等门控词表）：门控词表决定整句能否进入对应分支，漏一个口语说法（如 "放一下"）就会让整句落到 `else` 而不触发任何播放；新增分支须保持「停止/暂停/恢复/切歌 > 随机 > 类型 > 专辑 > 播放」的优先级顺序
- 改动音乐指令拦截（`isControlCommand` / `isPlayRequest` / 回复抑制窗口 / STT 免疫期 / 延迟兜底机制）：必须更新 `MessageDispatcherTest` 对应用例；注意五条不变式：① 音乐播放中「先分类指令、再决定停不停音乐」，顺序反了会让切歌类指令失效；② 控制类指令仅在 `musicActive=true` 时拦截，否则日常聊天（"你别说话"）会被吞掉并误发 abort；③ 本地指令命中后必须**同时**发 abort **和**开抑制窗口，只发 abort 不够（此时 `isLocalMusicPlaying` 已复位，在途 TTS 会照常播出）；④ 抑制窗口（`SERVER_REPLY_SUPPRESS_MS`）须覆盖服务器对同批语音尾巴开启的一轮完整回复（≥10s），且 `handleStt` 入口须把 `isReplySuppressed()` 纳入门控以丢弃免疫期内无唤醒词的 STT 尾巴，否则音乐一停该尾巴会落库并清掉抑制、触发 AI 接话（真机实测「这。」）；⑤ 音乐指令采用**延迟兜底机制**（`MUSIC_COMMAND_DELAY_MS=800ms`）：命中关键词后先落库透传给服务器 AI，同时启动本地兜底定时器；若服务器在窗口期内通过 MCP 调用 `self.music.*`，则 `cancelPendingMusicCommand()` 取消本地执行，超时后才本地兜底。控制词表（`STOP_KEYWORDS` 等）须覆盖口语说法（"别唱了"/"不听了"/"安静点"），漏词会让指令被当作聊天内容落库并触发 AI 接着闲聊
- 真机排障：Logcat 过滤 `XiaoZhiController`（连接/消息）、`AudioRecorder`（电平帧）、`XiaoZhiWebSocket`（WS 生命周期）、`MusicActionMapper`（点歌候选与命中）、`MessageDispatcher`（指令分类与抑制丢弃）、`AudioPipeline`（抑制窗口开关）、`[SM]` 前缀（状态迁移）

## 9. 延伸资料：Visbot 机器人功能调用开发指导文档

入口：[doc/Visbot功能调用开发指导文档/README.md](doc/Visbot功能调用开发指导文档/README.md)（`人类指导文档/` 面向开发者上手，`AI指导规范文档/` 为 API 权威参考）。

该文档集是优必选 Visbot 机器人（`com.ubtrobot.systemservice` Master 服务）的**物理实体功能调用**逆向成果，与本项目（小智语音协议）无代码耦合，属独立研究资料：

- 依据：官方 `rosa.jar` 反编译（`tmp/rosa_decompiled/`，10 个 Controller：servo/locomotion/motion/emotion/light/sensor/power/recharging/part/upgrade）+ 真机验证（`tmp/ubt_repo/` 现成可跑工程与系统签名 `keystore/platform.jks`）
- 调用链：`Robot.initialize(ctx)` → `Robot.globalContext().getSystemService(name)` → Promise 异步调用 → Binder → Master 服务 → 硬件
- 裸 IPC 协议：ContentProvider `com.ubtrobot.provider.master` 的 `connect` 方法换取 Binder，`transact(0x57524954 /*WRIT*/)` 发 `ParcelMessage(ParcelRequest)`（详见 AI 文档 08）
- 前置条件：系统签名 APK + `android:sharedUserId="android.uid.system"` + `com.ubtrobot.permission.ROBOT` 权限
- 改动机器人控制文档时：同步核对 `tmp/rosa_decompiled/` 反编译源码与 `tmp/ubt_repo/` 真机验证记录，避免编造 API 签名
