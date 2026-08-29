# 更新日志

本项目的所有重要变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
