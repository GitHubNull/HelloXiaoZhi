# 更新日志

本项目的所有重要变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.16.0] - 2026-09-09

### Added

- 新增歌手/乐队词典（`data/db/` Room 持久化，`artist_dict` 表）：内置华语/日韩/欧美歌手乐队种子条目与别名（`ArtistSeedData`），支持从文本文件导入歌手/乐队名单（每行一个名字、重复自动跳过）、导出与删除用户条目；`ArtistDictionary` 提供精确 → 别名 → 忽略大小写 → 模糊的多级匹配
- 新增音乐元数据推断器 `MusicMetadataInferrer`：ID3 标签缺失或为「未知」时，按「`.music_metadata.txt` 描述文件 → 文件路径 + 词典校验（父文件夹名/`歌手 - 歌名` 文件名模式）」推断歌手/专辑/类型；`MusicMetadataDescriptor` 解析无第三方依赖的类 YAML 目录描述文件；`MusicTrack` 新增 `album`/`metadataSource` 字段，扫描结果与推断来源入库
- 曲目元数据缓存由 Gson JSON 文件迁移至 Room `track_metadata_cache` 表，启动时异步恢复内存曲目（`restoreCacheAsync`），扫描目录前清除对应前缀旧缓存；`AppConfig.musicCacheVersion` 默认值提升至 1
- 音乐设置迁至独立页 `MusicSettingsActivity`（设置页新增「音乐设置」入口，原 MainActivity 内嵌目录选择与设置页内嵌控件移除）：集中管理功能开关、本地目录/SAF 文档树选择与扫描、曲目计数，以及歌手词典管理（内置条目展示/用户条目导入导出删除）
- 语音点歌支持按专辑播放（专辑关键词提取 + `MusicLibrary.searchByAlbum`）；指令解析增强：循环剥离开头量词（一首/这首歌…）与结尾标点，再剥离「的歌/歌曲/首歌」等装饰后缀（如「播放一首韩宝仪的歌。」可正确命中歌手韩宝仪）
- 新增单元测试：`ArtistDictionaryTest`、`MusicMetadataDescriptorTest`、`MusicMetadataInferrerTest`；扩充 `MusicActionMapperTest`、`MusicLibraryTest`、`RobotActionRegistryTest`、`McpActionHandlerTest`

### Fixed

- 修复语音点歌后服务器 AI 抢播它平台歌曲与本地音乐混音：本地音乐播放期间 `AudioPipeline.isLocalMusicPlaying` 丢弃服务器 `tts start` 与音频帧；`MessageDispatcher.onLocalMusicHandled` 在本地音乐指令命中时通知发送 AbortMessage 打断服务器 TTS
- 修复语音通话挂断后本地音乐仍在后台持续播放：`XiaoZhiController.stopVoiceCall` 现先置 `inVoiceCall=false` 再显式停止本地音乐播放（避免 `onMusicStop` 误恢复 TTS）

### Changed

- `RobotAction.executor` 签名由 `Boolean` 改为 `String?`：执行结果以 JSON 字符串作为 MCP `text content` 回发服务器，`RobotActionRegistry` 各动作改返 `OK_RESULT`
- 通话水波动画由圆角 + 边缘描边改为直边贴边绘制（移除 `CORNER` 圆角与 `xz_water_edge` 描边），消除小屏贴边时的四角留白
- 构建接入 Room 2.7.2 + KSP：`libs.versions.toml` 新增 room/ksp 依赖，`gradle.properties` 增设 `android.disallowKotlinSourceSets=false` 兼容 AGP 9 内置 Kotlin

## [0.15.1] - 2026-09-08

### Fixed

- 修复系统语音助手状态检测不准确：设置页「系统语音助手」卡片此前仅读取 `voice_interaction_service` 单键并用子串匹配，导致「已设为默认语音助手却显示未设置」。新增 `assistant/AssistantStatusDetector` 分层检测，依次并集「ROLE_ASSISTANT 角色公开 API → 角色反射兜底（`getRoleHolders` 隐藏 API）→ 标准 Secure 键（voice_interaction_service / assistant）→ 已知国产 ROM 私有键（一加 `oneplus_default_voice_assist_picker_service`）」，任一来源指向本应用即判定已设置；组件串统一经 `ComponentName` 解析后与包名精确相等，替代子串匹配避免误报。
- 检测未命中时输出诊断日志（各来源原始值 + 厂商/品牌/SDK），便于上报未覆盖的国产 ROM 私有键；`SettingsPageController.updateAssistantStatus()` 改接该检测器。
- 新增单元测试 `AssistantStatusDetectorTest`（15 个用例），覆盖各检测来源与 `flatRefersToPackage` 各种组件串形态及防误报。

## [0.15.0] - 2026-09-08

### Added

- 新增本地音乐播放控制功能（默认关闭，设置页「音乐播放」卡片开启）：
  - 新增 `music/` 包：`MusicPlayer`（MediaPlayer 播放引擎：播放/暂停/恢复/停止/上下首、播放队列、音频焦点管理，播放时经 `onMusicStart`/`onMusicStop` 与语音 TTS 播放协调互斥）、`MusicLibrary`（本地目录与 SAF 文档树递归扫描、MediaMetadataRetriever 元数据提取、曲目 JSON 缓存、按曲名/歌手/类型搜索）、`MusicActionMapper`（语音指令关键词降级方案：播放/暂停/继续/停止/切歌/随机/按类型等，命中即本地执行，不再走服务器流程）
  - `RobotActionRegistry` 注册 `self.music.*` MCP 动作工具（play/pause/resume/stop/next/previous/list），`RobotAction.Category` 新增 `MUSIC`；音乐播放期间暂停语音通话 TTS 播放，停止后自动恢复
  - 设置页新增「音乐播放」卡片：功能开关、本地目录/SAF 文档树选择（`takePersistableUriPermission` 持久化读取授权）、扫描按钮、曲目数与扫描状态显示
  - Manifest 新增 `READ_EXTERNAL_STORAGE` / `READ_MEDIA_AUDIO` 权限
- 新增单元测试：`MusicActionMapperTest`（各类指令关键词匹配与未匹配兜底）、`MusicLibraryTest`（本地/SAF 扫描、搜索、缓存）；`RobotActionRegistryTest` 补充 `self.music.*` 动作注册用例

## [0.14.0] - 2026-09-07

### Added

- AI 回复结束语（晚安/拜拜/再见/退下等中英文关键词）时自动挂断语音通话，且**语音播完才挂断**（不打断 AI 结束语）：
  - `MessageDispatcher` 对 `llm` 文本与 `tts` 句子做结束语关键词检测（`FAREWELL_KEYWORDS`，忽略大小写），命中即置 `pendingFarewell` 待处理标志
  - `AudioPipeline` 在播放队列播空（AI 语音播放完成）后消费该标志触发 `onAiFarewell`，经 `XiaoZhiController` 转发给通话页
  - `VoiceCallActivity` 收到回调自动挂断并复位机器人、恢复唤醒词检测：语音唤醒进入则退出到后台继续监听，文字聊天进入则经 `MainActivity.EXTRA_OPEN_CHAT_DETAIL` 自动返回聊天详情页（`handleIntent`/`onNewIntent`）
- 新增单元测试：`MessageDispatcherTest`（结束语检测：中英文关键词、大小写、`llm`/`tts` 双路径、空/`%` 控制文本不误判，共 19 个用例）

### Fixed

- 修复语音唤醒进入通话后，从文字聊天界面返回再进入时自动通话失效：`VoiceCallActivity` 处理 `EXTRA_AUTO_START_CALL` 时不再被 `isCallStarted()` 门控（此前 Activity 在后台存活时该标记会被丢弃）
- 修复非 Visbot 设备上 rosa.jar `Robot.initialize` 造成的 MST 后台重连刷屏：初始化前先经 `PackageManager.resolveContentProvider` 探测 Master 服务（`com.ubtrobot.provider.master`），缺失则短路跳过 SDK，不引入任何副作用

## [0.13.0] - 2026-09-07

### Added

- 新增 MCP（Model Context Protocol）机器人动作控制主链路（对齐 xiaozhi-esp32 的 MCP 实现，服务器端 AI 可主动调用机器人动作）：
  - hello 握手消息新增 `features.mcp` 设备能力声明；新增 `McpMessage` 外层封装与 `mcp` 下行消息分发，响应封装后回发服务器
  - 新增 `robot/` 动作抽象层：`RobotActionExecutor` 接口（头部/移动/表情/组合动作，`VisbotRobotController` 实现，便于单元测试 mock）、`RobotActionRegistry`（动作工具注册表，生成 `tools/list` 并按名执行 `tools/call`，命名空间 `self.robot.*`）、`McpActionHandler`（处理 `initialize` / `tools/list` / `tools/call` 三种 JSON-RPC 方法）
  - 原关键词匹配方案 `VisbotActionMapper` 降级为 MCP 不可用时的兜底，并新增移动动作关键词（前进/后退/左转/右转/停止）
  - 设置页「机器人动作」开关同时控制 MCP 主路径与关键词兜底
- 新增单元测试：`RobotActionRegistryTest`（mock 执行器验证注册/查找/执行与 tools/list 生成）、`McpActionHandlerTest`（三种 MCP 方法）；`MessagesTest` 补充 `Features` / `McpMessage` 用例

## [0.12.0] - 2026-09-06

### Added

- 新增 Visbot 机器人动作与表情控制（仅在优必选 Visbot 设备上生效，普通设备自动禁用）：
  - 内置 UBT SDK（`app/libs/rosa.jar`）与 `robot/` 包：`VisbotRobotController`（SDK 统一初始化与能力探测）、`VisbotServoController`（点头/摇头/抬头/低头/回中）、`VisbotMotionController`（移动）、`VisbotEmotionController`（表情，调用系统 SVGA 表情资源）
  - `VisbotActionMapper` 根据 AI 回复文本关键词自动匹配动作（同意/拒绝/打招呼等），由通话状态机驱动说话/静默表情切换，挂断通话时自动复位机器人
  - 设置页新增「机器人动作」卡片：开关（`AppConfig.robotActionEnabled`）与机器人服务可用状态显示
  - Manifest 声明系统共享 UID 与机器人控制权限，debug 构建启用平台系统签名（`platform.jks`，不入库）

### Fixed

- 修复进入通话瞬间用户前半句被漏识别：`AudioPipeline` 新增 1.5s 上行就绪窗口，listen start 到达服务器并激活服务器端 VAD 前丢弃上行帧（电平仍照常驱动声浪 UI）
- 修复未连接时直接进入通话导致指令丢失：`startVoiceCall` 与通话页入口先确保 WebSocket 已连接（`ensureConnected`）再发送 Abort/Listen
- 修复唤醒词检测暂停/恢复的麦克风抢占竞态：`WakeWordService.pause/resume` 改为同步直调进程内实例（原 `startService` Intent 异步排队，pause 尚未生效时通话录音可能与唤醒录音抢占麦克风）

## [0.11.1] - 2026-09-06

### Changed

- 超大文件结构重构（公共 API 与行为不变，纯拆分）：
  - `XiaoZhiController`（633 行）拆分为外观类 + 三个职责单一组件：`ConnectionManager`（连接/激活/切机）、`AudioPipeline`（音频上行/下行链路与增益）、`MessageDispatcher`（文本/音频消息分发）
  - `VoiceCallActivity` 拆分为协调者 + `VoiceCallViewBinder`（视图绑定）/ `VoiceCallAnimationController`（通话动画与计时）/ `SmallScreenOptimizer`（小屏收起策略）
  - `MainActivity` 的 Tab 切换逻辑提取为 `TabManager`
  - `WakeWordService` 拆分出 `WakeWordAudioRecorder`（麦克风采集与降噪）与 `WakeWordNotificationManager`（常驻通知）

## [0.11.0] - 2026-09-06

### Added

- 新增提示音反馈系统 `TonePlayer`（SoundPool 预加载，低延迟、不占额外线程）：唤醒成功播放三连音提示（`wake_success.wav`），AI 回答结束播放单音提示（`ai_done.wav`），音效资源置于 `res/raw/`
- 设置页新增「唤醒提示音」「AI 结束提示音」两个开关（默认开启，`AppConfig` 持久化），`XiaoZhiApp` 启动时预加载音效

### Fixed

- 修复通话页非挂断路径退出（返回键/最近任务划掉/系统回收）后唤醒词检测永久停摆：`VoiceCallActivity.onDestroy` 统一恢复 `WakeWordService`（此前仅 `hangUp` 恢复，其余路径退出后麦克风被释放，用户再也无法唤醒）
- 消除 AI 结束后的静默死区：收到 `tts stop` 时若播放队列已播空立即回 IDLE（原固定等待 800ms 宽限期，现降为 200ms 仅兜底尾音帧）；`AudioPlayer` 队列播空超时由 8s 收紧至 1.5s，AI 说完后不再有约 1 秒「不能开口」的停顿感

## [0.10.0] - 2026-09-06

### Added

- 新增完全离线的常驻唤醒词检测：内置 sherpa-onnx KWS 模型（`sherpa-onnx-kws-zipformer-wenetspeech-3.3M`，随 APK 打包），本地监听「阿妹阿妹」唤醒词，无需注册与联网，检测到后自动进入语音通话
  - 新增 `wake/` 包：`WakeWordService` 前台服务（microphone 类型、常驻通知、PARTIAL_WAKE_LOCK 防 CPU 休眠）与 `SherpaOnnxWakeWordEngine`（sherpa-onnx `KeywordSpotter` 的离线封装）
  - `XiaoZhiApp` 启动时按配置自动拉起检测服务；录音权限被回收时 `MainActivity` 检测到后自动关闭服务
  - 通话页支持唤醒场景：通过 `EXTRA_BOT_ID` / `EXTRA_AUTO_START_CALL` 指定唤醒目标机器人并自动开始通话，通话期间自动暂停检测避免麦克风冲突，挂断后恢复
- 新增系统语音助手集成（`assistant/` 包）：注册 `VoiceInteractionService` / `VoiceInteractionSessionService` / `RecognitionService`，可在系统设置中设为默认语音助手，通过长按 Home 键或系统手势唤醒小智
- 设置页新增「语音唤醒」开关与灵敏度滑块（0% ~ 100%），实时显示检测运行状态；新增「系统语音助手」默认状态检查与跳转系统设置入口
- 集成 sherpa-onnx 1.13.7（JitPack 分发，排除 JVM jar 避免与 Android AAR 内 Kotlin 类冲突）与 Robolectric 测试框架，新增 `SherpaOnnxWakeWordEngineTest` 单元测试

## [0.9.2] - 2026-09-06

### Fixed

- 修复挂断后仍可能出声：新增 `inVoiceCall` 静音闸门，`stopVoiceCall` 时置 false，后续迟到的 TTS 音频帧、`tts start`、挂起的 `resumePlayback` 全部被拦截，实现「挂断即静音」的真实电话逻辑

## [0.9.1] - 2026-09-03

### Fixed

- 修复启动时打开的机器人不符合预期：`BotRepository.defaultBot()` 优先级由「当前机器人 → 唤醒目标 → 第一个」改为「唤醒目标 → 当前机器人 → 第一个」，确保设置了唤醒目标时优先打开唤醒目标
- `XiaoZhiController` 初始化时将 `activeBotId` 同步到 `repository.activeBotId`，保持启动选择与实际打开一致

## [0.9.0] - 2026-09-03

### Added

- 新增 `OrientationPolicy`：原生横屏小面板（脸屏类真机）在 Activity 层显式请求横屏，避开厂商 ROM 强开传感器旋转导致被锁成竖屏
- 新增通话页状态浮层半透明胶囊背景 `bg_status_float.xml`（毛玻璃层色 50% alpha）

### Changed

- 移除主界面顶部导航栏（删除 `view_nav_bar.xml`），连接状态改由 Tab 栏聊天图标上的状态圆点指示
- 移除通话页顶部文字/动画切换栏，改为双击动画区域触发文字/动画模式切换
- 水波动画贴边绘制：`RippleCallView` 圆角收小（14dp→6dp）、边距缩小（18→2dp），减少左右/底部留白

### Fixed

- 修复 alps Visbot 等小屏原生横屏真机进入语音通话页时触发多次旋转动画的问题：`VoiceCallActivity` 在 manifest 静态声明 `screenOrientation="behind"` 继承 MainActivity 方向，移除其在 `onCreate()` 中的动态 `setRequestedOrientation` 调用，避免「窗口创建→配置变更」两阶段旋转；`OrientationPolicy` 在 API 23+ 改用 `Display.getMode()` 的物理面板尺寸判定原生方向，绕开厂商 ROM 把 natural orientation 上报为竖屏导致 `Display.getRotation()` 启发式误判的问题

## [0.8.0] - 2026-09-03

### Added

- 通话页水波动画改为双球独立驱动：用户球体由用户语音电平驱动，AI 球体由 AI 播放音频电平驱动（`RippleCallView` 新增 `setUserAudioIntensity` / `setAiAudioIntensity`）
- 新增 `XiaoZhiController.onAiWaveLevel` 回调：播放 AI 音频帧时计算 RMS 电平，供通话页 AI 球体动画使用

### Changed

- 涟漪生成由随机速度改为确定性驱动：涟漪速度由音频强度决定（强度越大速度越快），移除 `Random` 依赖
- `VoiceCallActivity` 将用户/AI 电平分别路由至 `RippleCallView` 两个球体的强度接口

### Fixed

- 非说话方球体音频强度自动衰减（×0.85），避免残留在非说话球体上

## [0.7.0] - 2026-09-03

### Added

- 新增文字/动画双模式切换开关：通话页顶部可实时切换「文字」与「动画」两种展示模式，文字模式展示结构化通话信息，动画模式展示水波涟漪动画（`RippleCallView`）
- 新增小屏通话页专项优化默认收起策略：`smallestScreenWidthDp < 360` 时，前 3 次进入保持完整界面并弹出 8 秒倒计时卡片（含连点提示），之后自动收起为纯动画界面；超过 3 次直接进入纯动画界面
- 新增小屏连点手势：纯动画界面下连点 2 次恢复完整界面、连点 3 次直接挂断并退回主页面
- 新增 `values-sw360dp` 资源分档，小屏与大屏尺寸按屏幕宽度自动适配

### Changed

- 整体 UI 由深色科技风重构为淡暖黄色宣纸风格（`colors.xml` / `themes.xml` / `activity_voice_call.xml`）
- 通话页动画由星河动画替换为水波涟漪动画（`RippleCallView` 替代 `StarfieldCallView`）
- 小屏下顶部状态栏、底部功能按钮栏及其内部按钮、文字整体收缩，使中间动画区域占满空间
- 通话页顶/底栏及内部控件尺寸改用 `dimens` 资源引用，支持多档屏幕适配

### Fixed

- 修复小屏收起态下灰色边框残留：收起时移除 `pool_frame` 背景并收紧涟漪内边距

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
