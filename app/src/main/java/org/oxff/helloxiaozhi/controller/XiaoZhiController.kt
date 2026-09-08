package org.oxff.helloxiaozhi.controller

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.oxff.helloxiaozhi.activation.ActivationFlow
import org.oxff.helloxiaozhi.chat.AbortMessage
import org.oxff.helloxiaozhi.chat.ChatEvent
import org.oxff.helloxiaozhi.chat.ChatMessage
import org.oxff.helloxiaozhi.chat.ChatState
import org.oxff.helloxiaozhi.chat.ChatStateMachine
import org.oxff.helloxiaozhi.chat.ConnectionStatus
import org.oxff.helloxiaozhi.chat.DetectMessage
import org.oxff.helloxiaozhi.chat.ListenMessage
import org.oxff.helloxiaozhi.config.AppConfig
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.net.OtaClient
import org.oxff.helloxiaozhi.net.XiaoZhiWebSocket
import org.oxff.helloxiaozhi.robot.McpActionHandler
import org.oxff.helloxiaozhi.robot.RobotActionRegistry
import org.oxff.helloxiaozhi.robot.VisbotActionMapper
import org.oxff.helloxiaozhi.robot.VisbotRobotController
import org.oxff.helloxiaozhi.music.MusicActionMapper
import org.oxff.helloxiaozhi.music.MusicLibrary
import org.oxff.helloxiaozhi.music.MusicPlayer
import org.oxff.helloxiaozhi.util.HandlerExecutor
import org.oxff.helloxiaozhi.util.HandlerSilenceScheduler
import org.oxff.helloxiaozhi.util.TonePlayer
import java.util.concurrent.TimeUnit

/**
 * 应用核心编排器（外观类）：协调各个专门组件完成业务逻辑。
 *
 * 职责：
 *  - 作为统一入口，协调 ConnectionManager、AudioPipeline、MessageDispatcher
 *  - 管理组件生命周期
 *  - 提供统一的 UI 回调接口
 *  - 处理跨组件的协调逻辑
 *
 * 重构说明：
 *  - 原始 XiaoZhiController (633 行) 拆分为 4 个类
 *  - 本类作为外观类，保持原有公共 API 不变
 *  - 具体业务逻辑委托给专门组件处理
 */
class XiaoZhiController(
    appContext: Context,
    val config: AppConfig,
    private val gson: Gson,
    private val repository: BotRepository,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // 状态机
    private val stateMachine = ChatStateMachine(
        HandlerExecutor(mainHandler),
        HandlerSilenceScheduler(mainHandler),
        object : ChatStateMachine.Callbacks {
            override fun sendAudioData(frame: ShortArray) {
                audioPipeline.sendAudioFrame(frame)
            }

            override fun sendTextData(message: Any) {
                ws.sendText(message)
            }

            override fun getSessionId(): String = connectionManager.sessionId

            override fun onEvent(event: ChatEvent) {
                when (event) {
                    ChatEvent.USER_START_SPEAKING -> audioPipeline.pausePlayback()
                    ChatEvent.AI_START_SPEAKING -> audioPipeline.resumePlayback()
                    ChatEvent.AI_STOP_SPEAKING -> {
                        if (config.aiDoneSoundEnabled) {
                            TonePlayer.playAiDoneTone()
                        }
                        if (audioPipeline.inVoiceCall && connectionManager.connectionStatus == ConnectionStatus.CONNECTED) {
                            ws.sendText(ListenMessage.start(connectionManager.sessionId))
                        }
                    }
                    else -> Unit
                }
            }

            override fun onUserWaveLevel(level: Float) {
                onUserWaveLevel?.invoke(level)
            }
        },
    ).apply {
        logger = { msg -> Log.i(TAG, "[SM] $msg") }
        onStateChanged = { state -> onChatStateChanged?.invoke(state) }
    }

    // WebSocket
    private val wsListener = object : XiaoZhiWebSocket.Listener {
        override fun onConnected() {
            connectionManager.onWebSocketConnected()
        }

        override fun onDisconnected() {
            connectionManager.onWebSocketDisconnected()
            resetStateMachine()
        }

        override fun onError(message: String) {
            connectionManager.onWebSocketError(message)
        }

        override fun onTextMessage(text: String) {
            messageDispatcher.handleTextMessage(text, connectionManager.activeBotId)
        }

        override fun onAudioFrame(opusData: ByteArray) {
            audioPipeline.handleAudioFrame(opusData)
        }
    }

    private val ws = XiaoZhiWebSocket(okHttp, config, gson, wsListener)

    // 专门组件
    private val connectionManager: ConnectionManager = ConnectionManager(config, repository, ws, ActivationFlow(OtaClient(okHttp, gson)), mainHandler)
    private val messageDispatcher: MessageDispatcher = MessageDispatcher(gson, repository, mainHandler, stateMachine)
    private val audioPipeline: AudioPipeline = AudioPipeline(audioManager, mainHandler, stateMachine, messageDispatcher)

    // Visbot 机器人控制（仅在 Visbot 设备上激活）
    val robotController = VisbotRobotController(appContext)

    // 音乐播放模块
    private val musicDatabase = org.oxff.helloxiaozhi.data.db.MusicDatabase.getInstance(appContext)
    val musicLibrary = MusicLibrary(appContext, musicDatabase).apply {
        // 启动时从 DB 缓存异步恢复内存曲目
        restoreCacheAsync()
    }
    val musicPlayer = MusicPlayer(appContext).apply {
        // 音乐播放时暂停 TTS 并屏蔽服务器音频，音乐停止时恢复
        onMusicStart = {
            // 标记本地音乐播放中，AudioPipeline 将丢弃服务器下发的 TTS 音频帧，
            // 避免服务器 AI 抢播它平台的歌与本地音乐混音
            audioPipeline.isLocalMusicPlaying = true
            if (audioPipeline.inVoiceCall) {
                audioPipeline.pausePlayback()
            }
        }
        onMusicStop = {
            audioPipeline.isLocalMusicPlaying = false
            if (audioPipeline.inVoiceCall) {
                audioPipeline.resumePlayback()
            }
        }
    }

    // MCP 动作系统（主路径：服务器 AI 主动调用）
    val robotActionRegistry = RobotActionRegistry(robotController, musicPlayer, musicLibrary).apply {
        logWarn = { msg -> Log.w("RobotActionRegistry", msg) }
        logError = { msg, e -> Log.e("RobotActionRegistry", msg, e) }
    }
    val mcpActionHandler = McpActionHandler(robotActionRegistry).apply {
        enabled = config.robotActionEnabled
        logInfo = { msg -> Log.i("McpActionHandler", msg) }
        logWarn = { msg -> Log.w("McpActionHandler", msg) }
        logDebug = { msg -> Log.d("McpActionHandler", msg) }
    }

    // 关键词匹配降级方案（MCP 不可用时兜底）
    val actionMapper = VisbotActionMapper(robotController).apply {
        enabled = config.robotActionEnabled
    }

    // 音乐关键词匹配降级方案
    val musicActionMapper = MusicActionMapper(musicPlayer, musicLibrary).apply {
        enabled = config.musicEnabled
    }

    // UI 回调
    var onConnectionStatusChanged: ((ConnectionStatus) -> Unit)? = null
    var onChatMessage: ((botId: String, message: ChatMessage) -> Unit)? = null
    var onChatStateChanged: ((ChatState) -> Unit)? = null
    var onUserWaveLevel: ((Float) -> Unit)? = null
    var onAiWaveLevel: ((Float) -> Unit)? = null
    var onActivationCodeRequired: ((String) -> Unit)? = null
    var onActivationCompleted: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** AI 回复结束语回调（用于自动挂断语音通话） */
    var onAiFarewell: (() -> Unit)? = null

    // 公开属性
    val connectionStatus: ConnectionStatus get() = connectionManager.connectionStatus
    val activeBotId: String? get() = connectionManager.activeBotId
    val chatState: ChatState get() = stateMachine.state

    init {
        // 连接组件回调
        connectionManager.onConnectionStatusChanged = { status ->
            onConnectionStatusChanged?.invoke(status)
        }
        connectionManager.onActivationCodeRequired = { code ->
            onActivationCodeRequired?.invoke(code)
        }
        connectionManager.onActivationCompleted = {
            onActivationCompleted?.invoke()
        }
        connectionManager.onError = { message ->
            onError?.invoke(message)
        }

        // 音频管道回调
        audioPipeline.onUserWaveLevel = { level ->
            onUserWaveLevel?.invoke(level)
        }
        audioPipeline.onAiWaveLevel = { level ->
            onAiWaveLevel?.invoke(level)
        }
        audioPipeline.onError = { message ->
            onError?.invoke(message)
        }
        audioPipeline.onSendAudioData = { data ->
            ws.sendOpus(data)
        }

        // 消息分发器回调
        messageDispatcher.onChatMessage = { botId, message ->
            onChatMessage?.invoke(botId, message)
            // AI 消息触发机器人动作映射（降级方案：MCP 不可用时关键词匹配）
            if (message.role == org.oxff.helloxiaozhi.chat.ChatRole.AI) {
                actionMapper.processMessage(message.content, isAiSpeaking = audioPipeline.isAiPlaying)
            }
        }
        messageDispatcher.mcpActionHandler = mcpActionHandler
        messageDispatcher.musicActionMapper = musicActionMapper
        messageDispatcher.onMcpResponse = { responseJson ->
            Log.i(TAG, "[WS] send mcp response: ${responseJson.take(300)}")
            ws.sendText(responseJson)
        }
        // 本地音乐指令匹配成功：发送 AbortMessage 打断服务器 TTS，
        // 防止服务器 AI 播放自己平台的音乐与本地音乐冲突
        messageDispatcher.onLocalMusicHandled = {
            Log.i(TAG, "[WS] local music handled, send abort to stop server TTS")
            ws.sendText(AbortMessage(sessionId = connectionManager.sessionId))
        }
        messageDispatcher.onHelloReceived = { sessionId, sampleRate ->
            connectionManager.onHelloReceived(sessionId)
            audioPipeline.onHelloReceived(sampleRate)
            // 通话中断线重连：用新 session 重发 listen start
            if (audioPipeline.inVoiceCall) {
                ws.sendText(ListenMessage.start(sessionId))
            }
        }
        messageDispatcher.onTtsStart = {
            audioPipeline.onTtsStart()
        }
        messageDispatcher.onTtsStop = {
            audioPipeline.onTtsStop()
        }
        messageDispatcher.onUserStartSpeaking = {
            // 用户开始说话时的额外处理
        }
        // AI 结束语回调由 AudioPipeline 在播放完成后触发（见 audioPipeline.onAiFarewell）
        audioPipeline.onAiFarewell = {
            // AI 回复结束语，通知 UI 自动挂断
            onAiFarewell?.invoke()
        }

        // 状态机状态变更联动机器人表情
        stateMachine.onStateChanged = { state ->
            onChatStateChanged?.invoke(state)
            actionMapper.onChatStateChanged(state)
        }
    }

    // ---------------- 连接管理（委托给 ConnectionManager） ----------------

    fun ensureConnected() = connectionManager.ensureConnected()
    fun activationCheckNow() = connectionManager.activationCheckNow()
    fun cancelActivation() = connectionManager.cancelActivation()
    fun applySettings() = connectionManager.applySettings()
    fun switchActiveBot(botId: String) {
        audioPipeline.stopVoiceCall()
        connectionManager.switchActiveBot(botId)
    }
    fun isActiveBotActivated(): Boolean = connectionManager.isActiveBotActivated()
    fun requestActivationCodeFor(mac: String, callback: (code: String?, error: String?) -> Unit) =
        connectionManager.requestActivationCodeFor(mac, callback)

    // ---------------- 聊天 ----------------

    fun sendTextMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        if (stateMachine.state == ChatState.AI_SPEAKING) {
            ws.sendText(AbortMessage(sessionId = connectionManager.sessionId))
            audioPipeline.pausePlayback()
        }
        ws.sendText(DetectMessage(text = content))
    }

    // ---------------- 语音通话（委托给 AudioPipeline） ----------------

    fun startVoiceCall() {
        // 确保 WebSocket 已连接，未连接时先连接
        if (connectionManager.connectionStatus != ConnectionStatus.CONNECTED) {
            connectionManager.ensureConnected()
        }
        ws.sendText(AbortMessage(sessionId = connectionManager.sessionId))
        ws.sendText(ListenMessage.start(connectionManager.sessionId))
        audioPipeline.startVoiceCall(connectionManager.sessionId)
    }

    fun stopVoiceCall() {
        ws.sendText(AbortMessage(sessionId = connectionManager.sessionId))
        ws.sendText(ListenMessage.stop(connectionManager.sessionId))
        audioPipeline.stopVoiceCall()
        // 挂断时停止本地音乐播放：MusicPlayer 的 MediaPlayer 独立于 AudioPipeline 生命周期，
        // 若用户在通话中通过语音指令播放了本地音乐，挂断后必须显式停止，否则音乐会在后台持续播放，只能杀进程终止。
        // 顺序上先 audioPipeline.stopVoiceCall() 置 inVoiceCall=false，再停音乐：
        // onMusicStop 回调里带的 inVoiceCall 条件会命中 false，从而跳过 resumePlayback，不会误恢复 TTS。
        if (musicPlayer.state != org.oxff.helloxiaozhi.music.PlaybackState.IDLE) {
            Log.i(TAG, "[WS] stopVoiceCall: stopping local music playback")
            musicPlayer.stop()
        }
    }

    fun setPlaybackGain(gain: Float) = audioPipeline.setPlaybackGain(gain)
    fun setMicGainDb(db: Float) = audioPipeline.setMicGainDb(db)

    // ---------------- 生命周期 ----------------

    fun shutdown() {
        audioPipeline.shutdown()
        stateMachine.destroy()
        ws.disconnect()
        scope.cancel()
        TonePlayer.release()
        musicPlayer.release()
        musicLibrary.shutdown()
    }

    private fun resetStateMachine() {
        stateMachine.reset()
    }

    private companion object {
        const val TAG = "XiaoZhiController"
    }
}
