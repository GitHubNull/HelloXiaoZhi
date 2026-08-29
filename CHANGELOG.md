# 更新日志

本项目的所有重要变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
