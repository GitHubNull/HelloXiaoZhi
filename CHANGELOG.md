# 更新日志

本项目的所有重要变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.6.2] - 2026-08-30

### Fixed

- 修复应用图标重复资源导致构建失败：mipmap 各密度目录下同时存在 `ic_launcher.png` 与 `ic_launcher.webp`（含 `ic_launcher_round`），资源合并报 Duplicate resources
- 修复圆形图标红色双环被裁切：round 图标由「内容缩至 68% 居中 + 圆形遮罩」改为「整图填充圆形」，主体完整居中
- 修复 adaptive 图标双层叠加重影：background 由整幅插画改为纯色背景（从源图边缘采样暖白色），避免 background 中的主体图案透过 foreground 透明区域显示

### Changed

- 重新生成全套应用图标：legacy `ic_launcher.webp` / `ic_launcher_round.webp` 按密度缩放（48~192px），adaptive foreground 缩至 61% 安全区居中，新增 `drawable-nodpi/ic_launcher_bg.webp` 纯色背景

## [0.6.1] - 2026-08-30

### Changed

- 仓库结构重构：`agents.md` 重命名为 `AGENTS.md`（GitHub AI 代理上下文文件标准命名），README 项目结构同步更新
- 文档清理：`README.md` / `AGENTS.md` / `tools/asr/README.md` 移除参考项目的大篇幅介绍（技术栈、启动教程、模块对照表），仅保留致谢与第三方声明；「WebUI 代理」表述统一改为「自定义代理」
- 代码注释清除「对应 Web 端」溯源标注（`AudioPlayer`、`AudioRecorderManager`、`WavParser`、`ChatStateMachine`、`XiaoZhiController`、`XiaoZhiWebSocket`、`AudioMath`、`AudioMathTest`）

### Removed

- 移除参考实现副本目录 `ref/xiaozhi-webui-master`（66 个文件）：解除 Git 跟踪并移出仓库，保留本地未跟踪副本供离线查阅

## [0.6.0] - 2026-08-30

### Added

- 新增 GitHub Actions 自动化发布工作流（`.github/workflows/release.yml`）：仅当推送符合语义化版本规范的标签（如 `1.0.0`、`1.0.0-alpha.1`、`1.0.0-beta.2`）时触发，自动构建 Release APK + AAB，基于标签间提交历史按 Conventional Commits 中文分组生成更新日志，并发布至 GitHub Releases（含 `-alpha`/`-beta`/`-rc` 后缀自动标记 Prerelease、SHA256 校验文件）；另支持 `workflow_dispatch` 手动触发
- 新增更新日志生成脚本（`.github/scripts/gen-release-notes.sh`）：定位上一个标签计算提交范围，无上一标签时兜底全量历史，文末附 Full Changelog 对比链接

### Changed

- Release buildType 挂载 `debug` 签名配置：CI 产出的 Release APK 以 runner 自动生成的 debug keystore 签名，保证可直接安装（后续正式发版可升级为签名密钥 secrets 方案）

## [0.5.0] - 2026-08-30

### Added

- 新增上行语音增强器 `MicEnhancer`（audio/）：帧级 AGC（轻声帧放大逼近目标峰值电平 0.3，上限 +24dB；大声帧衰减防削波，下限 -12dB）+ 噪声门（低于「底噪 × 2」的帧衰减至 0.1 倍，防背景噪声误触发服务器端 VAD）；底噪由首帧引导初始化，仅「底噪 × 4 以下」的帧参与跟踪（持续轻声不会被误学成底噪）；诊断字段 `lastGain`/`currentNoiseFloor`/`currentEstPeak` 每 100 帧随电平日志输出（`enh[...]` 段）
- 音频源自动降级：优先 `VOICE_COMMUNICATION`（内置 AEC/NS），连续 150 帧零电平自动降级 `MIC` 并手动挂 `AcousticEchoCanceler` + `NoiseSuppressor`（失败静默降级）
- 新增 `MicEnhancerTest` 单元测试（AGC 放大/衰减、噪声门抑制、底噪跟踪不变式）

### Changed

- 上行链路顺序调整：电平（原始帧）→ `MicEnhancer.process` → 用户增益；增强器只作用于上行帧，不影响送给状态机的 VAD 电平

### Fixed

- 修复通话页增益滑块初始显示与实际不符：初始进度与 recorder 默认值对齐（50% = 1.0x / 0dB），并先设进度再注册监听避免初始化触发 `onGain`

### Docs

- `agents.md` 同步：新增「上行增强」模块说明、音频源降级细节、增强器调参不变式（底噪跟踪/电平解耦）、测试清单更新

## [0.4.1] - 2026-08-30

### Added

- 正在查看的对话到达 AI 消息不计未读：`BotRepository.visibleBotId`（运行时状态，不落盘）由对话详情页 open/close 维护，查看期间回复不再产生角标提醒（预览与时间戳仍更新、消息仍落库）

### Fixed

- 修复未读角标不实时更新：`MainActivity` 订阅 `repository.onDataChanged`，清零/累加未读后立即刷新角标与会话列表（此前要等下一次 `onResume`）；`onDestroy` 解绑避免 Activity 泄漏
- 修复通话计时恒为 0：`VoiceCallActivity` 计时器在 `callStarted` 置位后才启动，避免首次执行命中 `if (!callStarted) return` 后计时链永久中断

## [0.4.0] - 2026-08-30

### Changed

- 语音通话状态机重构为**服务器端 VAD 驱动**（对齐参考 APP auto 模式）：移除客户端 VAD 阈值检测（`THRESHOLD_SPEAKING`/`THRESHOLD_INTERRUPT`）、预触发缓冲（`PRE_ROLL_*`）与客户端打断检测，状态由服务器消息驱动（`stt` → `USER_SPEAKING`、`tts start` → `AI_SPEAKING`）
- `listen start/stop` 改为通话生命周期管理：`startVoiceCall` 时发送一次 `listen start`（mode=auto 整个通话保持监听），`stopVoiceCall` 时发送 `listen stop`，不再随状态迁移重复发送（修复真机死锁：服务器等 listen start 才处理音频，旧逻辑等 stt 才发 listen start）
- AI 播放时完全不上行：新增 `isAiPlaying` 标志，TTS 播放期间丢弃所有上行音频帧（避免 TTS 泄漏污染服务器端 VAD）
- 每轮 AI 回复结束（`AI_STOP_SPEAKING`）后重发 `listen start`：修复「只有第一轮被识别，后续语音要等挂断才被一次性识别」
- 通话中断线重连后（`handleHello`）重发 `listen start`：修复重连后语音静默失效
- `TTS_PLAY_DELAY_MS` 由 1500ms 收紧至 300ms；移除 Opus 编码器冷启动预热（参考 APP 无此逻辑）

### Fixed

- 修复多轮对话失效：AI 播放结束后未重新开启服务器监听，后续语音需等挂断时才被一次性识别
- 修复重连后语音静默：新 session 建立后未恢复服务器监听

### Docs

- `HelloMessage` 注释补充 `version=1 + response_mode=manual` 组合不被官方/自建代理服务器识别的教训；`agents.md` 状态机文档同步更新；ASR 测试体系（`MockAsrServer`、`AsrTestRunner`、各套件）对齐服务器 VAD 驱动模型

## [0.3.3] - 2026-08-29

### Fixed

- 修复底部 Tab 栏角标多位数字被裁剪：`view_tab_bar.xml` 图标容器由固定 24dp 改为自适应生长（`wrap_content` + `minWidth/minHeight 24dp`），图标 `layout_gravity=center` 保持居中

## [0.3.2] - 2026-08-29

### Fixed

- 修复通话页挂断按钮渐变角度不兼容：`bg_hangup.xml` 渐变 `angle` 由 305 修正为 315（Android 8.0 及以下要求 angle 为 45 的倍数，305 会导致渐变渲染异常），并加注释防止改回

## [0.3.1] - 2026-08-29

### Fixed

- 修复句首轻声识别丢失：说话判定阈值 `THRESHOLD_SPEAKING` 由 0.02 下调至 0.015，防抖帧数 3→4（增加抗噪）
- 修复打断 AI 时句首丢失：AI_SPEAKING 期间帧重新缓存进预触发缓冲（16 帧），打断时补发缓冲帧 + 确认帧，避免「那我有个问题…」只识别到后半句（VOICE_COMMUNICATION + 硬件 AEC 下 TTS 残留电平远低于打断阈值，补发安全）
- 修复通话首句句首字符丢失：Opus 编码器冷启动预热（预编码 20 帧静音丢弃，SILK 模式收敛）
- 修复识别幻觉词：首条 `stt` 到达时若客户端仍在 `USER_SPEAKING` 立即收尾（发 listen stop + 停上行），避免尾部静音/呼吸噪声被服务器识别为「嗯。」「对。」等第二句

## [0.3.0] - 2026-08-29

### Added

- ASR 测试评估体系（非侵入，仅测试入口）：`MockAsrServer` 模拟小智 WebSocket 服务器、`MetricsEngine` 评分引擎、`BottleneckAnalyzer` 瓶颈分析、`AudioFingerprint` 指纹比对、`ChineseSpeechSynthesizer` 中文语音合成、`SubtitleParser` 字幕解析、`VirtualAudioSource` 虚拟音频源
- 7 个评估测试套件：基础功能 / 中文轻声 / 打断 / 噪声鲁棒 / 真实语音 / 压力 / VAD 边界（`app/src/test/java/org/oxff/helloxiaozhi/asr/`）
- Gradle 任务：`gradlew asrTest` 只运行 ASR 评估测试（常规 test 不受影响）；`asrFetchResources` 从开放平台下载真实音频（uv 虚拟环境）
- 测试音频资源与用例：合成音频（标准/中文轻声）、真实访谈片段（含字幕）、synthetic/real_world 测试用例 JSON
- `tools/asr/` Python 工具链：真实资源抓取（B 站）、字幕对齐、合成音频生成、会话提取（附 README 与 requirements.txt）

### Changed

- `gradle.properties` 钉住 JDK 21 工具链（`auto-detect=false` + `java.home`），命令行构建无需再传 `-D` 参数
- 新增 mockwebserver 测试依赖（版本对齐 okhttp 4.12.0）；`.gitignore` 排除 ASR 会话凭证、报告、下载中间产物与 `.venv-asr/`

## [0.2.2] - 2026-08-29

### Fixed

- 修复「添加机器人」模态框头像网格在窄屏上最右列被裁切：网格宽度改为按模态卡片与弹出层 padding 动态计算，窄屏等比收缩、宽屏钉在 248dp 上限，单元格尺寸由网格宽度直接算出保证正方形，收窄后居中显示

## [0.2.1] - 2026-08-29

### Fixed

- 修复详情页打开时 Tab 切换无响应：详情页是不透明覆盖层，切换前未关闭导致新页面被遮挡
- 修复关闭详情页后软键盘残留：详情页输入框持有焦点，关闭时未收起键盘

## [0.2.0] - 2026-08-29

### Added

- 多机器人管理：`BotRepository` 数据层（JSON 文件持久化、debounce 落盘、原子写、损坏回落 seed），支持添加/删除/切换机器人，按机器人切换设备身份（MAC/ClientId）并重连
- 三 Tab 界面重构：聊天 / 通讯录 / 设置页（`ui/page/` 控制器 + `MainActivity` 外壳），对话详情滑入层
- 通话页升级：二进制星河动画（`StarfieldCallView`）、声浪动画（`WaveBarsView`）、历史通话记录、播放/上行增益控制（`playbackGain`，上行增益与 VAD 解耦）
- 模态框体系：`ActivationModal` / `AddBotModal` / `ConfirmModal`（替代旧 `ActivationDialog`），支持「获取该 MAC 的激活码」单次探测（`ActivationFlow.probeOnce`）
- 自定义 View 组件：`SlideInContainer`、`XzSwitch`、`ToastHost`、`ModalHost`、`Pressable`、`BubbleDrawables`、`AvatarPalette`
- 工具类：`MacGenerator`（MAC 生成与校验）、`TimeFormat`（时间展示）
- 连接状态新增 `CONNECTING`；`ChatStateMachine` 新增 `onStateChanged` 钩子驱动通话页动画
- 新增 RecyclerView 依赖；新增测试 `BotRepositoryTest`、`MacGeneratorTest`、`TimeFormatTest`、`AvatarPaletteTest`

### Changed

- 说话判定阈值 `THRESHOLD_SPEAKING` 由 0.04 下调至 0.02（句首轻声优化）
- `XiaoZhiWebSocket.connect(deviceId)` 钉住设备身份，重连复用；`XiaoZhiController.switchActiveBot(botId)` 按「取消激活轮询 → 断开 → 改身份 → 重连」顺序执行
- 移除旧 UI（`SettingsActivity`、`ActivationDialog` 及其布局/资源），`AppConfig` 新增 `clear()` 供重置

## [0.1.0] - 2026-08-25

### Added

- `ListenMessage` 增加 `session_id` 字段，对齐 ESP32 协议（`SendStartListening`/`SendStopListening`）
- TTS 延迟播放机制（1.5s），避免服务器端 VAD 将 TTS 开头误判为用户语音导致自问自答
- WebSocket 断开时重置状态机，避免重连后状态机状态与实际不符（如仍停留在 `AI_SPEAKING` 无法响应语音）
- `tts stop` 宽限期机制：尾音帧入队后若队列已播空立即回归 `IDLE`，无需等待播放器超时

### Fixed

- 修复「只有第一句 AI 回答有声音」：`resumePlayback()` 后未显式调用 `AudioTrack.play()`，写入 paused 状态 track 的数据被缓冲但不发声
- 修复语音通话回声：播放通路由 `USAGE_MEDIA` 改为 `USAGE_VOICE_COMMUNICATION`（`MODE_IN_COMMUNICATION`），硬件 AEC 才能获取下行参考信号消除回声
- 修复 `AudioTrack` underrun 被系统禁用后一直静音：检测到写入失败自动重建 track，PCM 改为阻塞写入（对齐官方 ESP32 `OutputAudio` 行为，避免攒 buffer 造成延迟）
- 修复播放队列播空误判：`EMPTY_TIMEOUT_MS` 由 500ms 放宽至 8s，避免 TTS 句间停顿（5-7s）被误判为播放结束
- 修复播放缓冲与请求不符：放弃低延迟 fast track（`PERFORMANCE_MODE_NONE`），普通通路下请求的缓冲大小才真实生效

### Changed

- 更新应用图标（自适应图标前景图全套 mipmap 尺寸）与 README 横幅素材
- README 协议参考链接由 `xiaozhi-esp32` 更新为 `xiaozhi`
- 音频诊断日志增强：下行帧间隔/总时长统计、播放队列堆积预警、欠载（underrun）预警、上行录音 AEC 状态日志
