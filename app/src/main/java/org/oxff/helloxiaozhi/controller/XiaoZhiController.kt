package org.oxff.helloxiaozhi.controller

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import org.oxff.helloxiaozhi.activation.ActivationFlow
import org.oxff.helloxiaozhi.audio.AudioPlayer
import org.oxff.helloxiaozhi.audio.AudioRecorderManager
import org.oxff.helloxiaozhi.audio.OpusCodec
import org.oxff.helloxiaozhi.audio.WavParser
import org.oxff.helloxiaozhi.chat.AbortMessage
import org.oxff.helloxiaozhi.chat.ChatEvent
import org.oxff.helloxiaozhi.chat.ChatMessage
import org.oxff.helloxiaozhi.chat.ChatRole
import org.oxff.helloxiaozhi.chat.ChatState
import org.oxff.helloxiaozhi.chat.ChatStateMachine
import org.oxff.helloxiaozhi.chat.ConnectionStatus
import org.oxff.helloxiaozhi.chat.DetectMessage
import org.oxff.helloxiaozhi.chat.HelloResponse
import org.oxff.helloxiaozhi.chat.LlmMessage
import org.oxff.helloxiaozhi.chat.SttMessage
import org.oxff.helloxiaozhi.chat.TtsMessage
import org.oxff.helloxiaozhi.config.AppConfig
import org.oxff.helloxiaozhi.net.OtaClient
import org.oxff.helloxiaozhi.net.XiaoZhiWebSocket
import org.oxff.helloxiaozhi.util.DeviceInfoProvider
import org.oxff.helloxiaozhi.util.HandlerExecutor
import org.oxff.helloxiaozhi.util.HandlerSilenceScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 应用核心编排器，整合 ref Web 端 App.vue 的组装逻辑与后端代理职责：
 *
 *  - 连接流程：官方直连模式先走 OTA 注册/验证码激活，再建 WebSocket；
 *    自定义服务器模式直接连接
 *  - 消息分发：文本帧按 type 分发（hello/stt/llm/tts）；二进制帧按魔数
 *    分流（RIFF = 代理下发的 WAV，否则 = 官方直连的原始 Opus）
 *  - 语音链路：录音帧 → 电平 → 状态机 → Opus 编码上行；Opus 解码 →
 *    播放队列下行
 *  - 状态机事件：用户开口暂停播放，AI 开始说话恢复播放，队列播空回 IDLE
 *
 * 所有 UI 回调（on*）保证在主线程触发。
 */
class XiaoZhiController(appContext: Context) {

    val config = AppConfig(appContext)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val otaClient = OtaClient(okHttp, gson)
    private val activationFlow = ActivationFlow(otaClient, config)
    private val player = AudioPlayer()

    private var recorder: AudioRecorderManager? = null
    private var opusEncoder: OpusCodec? = null
    private var opusDecoder: OpusCodec? = null
    private var decodeSampleRate = 16000
    private var audioFrameCount = 0
    private var firstAudioFrameAt = 0L
    private var lastAudioFrameAt = 0L

    private val stateMachine = ChatStateMachine(
        HandlerExecutor(mainHandler),
        HandlerSilenceScheduler(mainHandler),
        object : ChatStateMachine.Callbacks {
            override fun sendAudioData(frame: ShortArray) {
                opusEncoder?.encode(frame)?.let { ws.sendOpus(it) }
            }

            override fun sendTextData(message: Any) {
                ws.sendText(message)
            }

            override fun getSessionId(): String = sessionId

            override fun onEvent(event: ChatEvent) {
                when (event) {
                    // 用户开口：停止播放并清空队列（对应 App.vue USER_START_SPEAKING）
                    ChatEvent.USER_START_SPEAKING -> player.pausePlayback()
                    // AI 开始说话：延迟恢复播放（避免服务器端 VAD 把 TTS 开头误判为用户语音）
                    ChatEvent.AI_START_SPEAKING -> mainHandler.postDelayed({
                        player.resumePlayback()
                    }, TTS_PLAY_DELAY_MS)
                    else -> Unit
                }
            }

            override fun onUserWaveLevel(level: Float) {
                onUserWaveLevel?.invoke(level)
            }
        },
    ).apply {
        // 状态迁移日志（真机诊断用）
        logger = { msg -> Log.i(TAG, "[SM] $msg") }
    }

    @Volatile
    var sessionId = ""
        private set

    @Volatile
    var connectionStatus = ConnectionStatus.DISCONNECTED
        private set

    /** 当前语音通话状态（UI 直接读取） */
    val chatState: ChatState get() = stateMachine.state

    // ---------------- UI 回调（主线程触发） ----------------

    var onConnectionStatusChanged: ((ConnectionStatus) -> Unit)? = null
    var onChatMessage: ((ChatMessage) -> Unit)? = null
    var onChatStateChanged: ((ChatState) -> Unit)? = null
    var onUserWaveLevel: ((Float) -> Unit)? = null

    /** 官方模式需要激活时回调（code = 6 位验证码） */
    var onActivationCodeRequired: ((String) -> Unit)? = null

    /** 激活完成（对话框可关闭） */
    var onActivationCompleted: (() -> Unit)? = null

    /** 错误提示（可直接 Toast） */
    var onError: ((String) -> Unit)? = null

    /** WebSocket 监听器（先于 ws 声明：ws 构造需要引用） */
    private val wsListener = object : XiaoZhiWebSocket.Listener {

        override fun onConnected() {
            mainHandler.post { setConnectionStatus(ConnectionStatus.CONNECTED) }
        }

        override fun onDisconnected() {
            mainHandler.post {
                sessionId = ""
                setConnectionStatus(ConnectionStatus.DISCONNECTED)
                // WebSocket 断开后重置状态机状态：避免重连后状态机状态与实际不符，
                // 导致无法正常工作（如重连后状态机仍认为在 AI_SPEAKING，无法响应用户语音）
                resetStateMachine()
            }
        }

        override fun onError(message: String) {
            mainHandler.post {
                setConnectionStatus(ConnectionStatus.ERROR)
                onError?.invoke(message)
            }
        }

        override fun onTextMessage(text: String) {
            handleTextMessage(text)
        }

        override fun onAudioFrame(opusData: ByteArray) {
            handleAudioFrame(opusData)
        }
    }

    private val ws = XiaoZhiWebSocket(okHttp, config, gson, wsListener)

    init {
        // 首次启动生成设备 ID（MAC 格式，对应 config.py 的 DEVICE_ID）
        if (config.deviceId.isBlank()) {
            config.deviceId = DeviceInfoProvider.obtainDeviceId(appContext)
        }
        // 播放队列播空 → 回到 IDLE（对应 Web 端 onQueueEmpty）
        player.onQueueEmpty = {
            mainHandler.post {
                if (stateMachine.state == ChatState.AI_SPEAKING) {
                    stateMachine.setState(ChatState.IDLE)
                }
            }
        }
        // 播放会话常驻（聊天模式的 TTS 回复也需要播放，对应 Web 端
        // AudioService 随应用初始化）
        player.startSession()
    }

    // ---------------- 连接管理 ----------------

    /** 确保 WebSocket 已连接（官方模式先走激活流程） */
    fun ensureConnected() {
        if (connectionStatus == ConnectionStatus.CONNECTED) return
        if (config.isOfficialMode()) {
            activationFlow.ensureActivated(activationListener)
        } else {
            connectWebSocket()
        }
    }

    /** 用户点击"我已添加设备"后立即检查一次激活状态 */
    fun activationCheckNow() = activationFlow.requestCheckNow()

    /** 用户取消激活（关闭对话框） */
    fun cancelActivation() = activationFlow.cancel()

    /** 设置变更后应用：断开重连（下次 ensureConnected 生效） */
    fun applySettings() {
        activationFlow.cancel()
        ws.disconnect()
        setConnectionStatus(ConnectionStatus.DISCONNECTED)
    }

    private fun connectWebSocket() {
        ws.connect()
    }

    private val activationListener = ActivationFlow.Listener().apply {
        onCodeRequired = { code ->
            mainHandler.post { onActivationCodeRequired?.invoke(code) }
        }
        onActivated = { code ->
            mainHandler.post {
                onActivationCompleted?.invoke()
                // 激活完成后必须新建 WebSocket 连接（官方协议要求）
                connectWebSocket()
            }
        }
        onError = { message ->
            mainHandler.post {
                setConnectionStatus(ConnectionStatus.ERROR)
                onError?.invoke(message)
            }
        }
    }

    // ---------------- 聊天 ----------------

    /** 发送文字消息（对应 App.vue sendMessage） */
    fun sendTextMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        if (stateMachine.state == ChatState.AI_SPEAKING) {
            // AI 正在说话：先打断再发送（对应 App.vue sendMessage）
            ws.sendText(AbortMessage(sessionId = sessionId))
            player.pausePlayback()
        }
        ws.sendText(DetectMessage(text = content))
    }

    // ---------------- 语音通话 ----------------

    /** 进入语音通话（对应 App.vue showVoiceCallPanel） */
    fun startVoiceCall() {
        Log.i(TAG, "startVoiceCall: state=${stateMachine.state}, sessionId=$sessionId")
        ws.sendText(AbortMessage(sessionId = sessionId))
        player.pausePlayback()
        if (stateMachine.state != ChatState.IDLE) {
            stateMachine.setState(ChatState.IDLE)
        }
        val encoder = opusEncoder ?: OpusCodec.encoder().also { opusEncoder = it }
        recorder = AudioRecorderManager(
            onFrame = { frame, level -> stateMachine.handleAudioLevel(level, frame) },
            onError = { message ->
                mainHandler.post { onError?.invoke(message) }
            },
            audioManager = audioManager,
        ).also { it.start() }
    }

    /** 退出语音通话（对应 App.vue closeVoiceCallPanel） */
    fun stopVoiceCall() {
        ws.sendText(AbortMessage(sessionId = sessionId))
        recorder?.stop()
        recorder = null
        player.pausePlayback()
        stateMachine.reset()
    }

    /** 应用退出清理 */
    fun shutdown() {
        recorder?.stop()
        recorder = null
        player.stopSession()
        stateMachine.destroy()
        ws.disconnect()
        activationFlow.shutdown()
        opusEncoder?.close()
        opusEncoder = null
        opusDecoder?.close()
        opusDecoder = null
    }

    // ---------------- 消息处理 ----------------

    /** 在 OkHttp 回调线程解析文本消息；UI 副作用投递主线程 */
    private fun handleTextMessage(text: String) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            return // 非 JSON 文本直接忽略（对应后端 JSONDecodeError 兜底）
        }
        val type = json.get("type")?.asString
        if (type != "hello") Log.i(TAG, "[WS] text type=$type")
        when (type) {
            "hello" -> handleHello(json)
            "stt" -> {
                val message = gson.fromJson(json, SttMessage::class.java)
                message.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    Log.i(TAG, "[WS] stt text=「$it」")
                    appendChat(ChatRole.USER, it)
                }
            }
            "llm" -> {
                val message = gson.fromJson(json, LlmMessage::class.java)
                message.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    Log.i(TAG, "[WS] llm text=「$it」")
                    appendChat(ChatRole.AI, it)
                }
            }
            "tts" -> handleTts(json)
            else -> Unit
        }
    }

    /** 服务器握手应答：记录 session_id，按服务器采样率重建解码器 */
    private fun handleHello(json: JsonObject) {
        val hello = gson.fromJson(json, HelloResponse::class.java)
        hello.sessionId?.let { sessionId = it }
        val rate = hello.audioParams?.sampleRate ?: DEFAULT_SAMPLE_RATE
        Log.i(TAG, "[WS] hello: sessionId=$sessionId, sampleRate=$rate")
        if (rate != decodeSampleRate) {
            decodeSampleRate = rate
            opusDecoder?.close()
            opusDecoder = OpusCodec.decoder(rate)
            player.setSampleRate(rate)
        }
    }

    /** TTS 状态消息（对应 App.vue tts 分支） */
    private fun handleTts(json: JsonObject) {
        val tts = gson.fromJson(json, TtsMessage::class.java)
        when (tts.state) {
            TtsMessage.STATE_START -> {
                mainHandler.post {
                    if (stateMachine.state == ChatState.IDLE) {
                        stateMachine.setState(ChatState.AI_SPEAKING)
                    }
                }
            }
            TtsMessage.STATE_SENTENCE_START -> {
                val text = tts.text?.trim().orEmpty()
                // 以 % 开头的控制文本不展示（对应 App.vue blackList）
                if (text.isNotEmpty() && !text.startsWith("%")) {
                    Log.i(TAG, "[WS] tts sentence=「$text」")
                    appendChat(ChatRole.AI, text)
                }
            }
            TtsMessage.STATE_STOP -> {
                // 服务器结束本次 TTS：尾音帧可能还在路上，延迟一小段
                // 若队列已播空则立即回 IDLE，无需等播放器超时
                mainHandler.postDelayed({
                    if (stateMachine.state == ChatState.AI_SPEAKING && player.isQueueEmpty()) {
                        stateMachine.setState(ChatState.IDLE)
                    }
                }, TTS_STOP_GRACE_MS)
            }
            else -> Unit
        }
    }

    /**
     * 延迟播放 TTS 音频帧：listen stop 后服务器端 VAD 仍处理缓冲的音频帧，
     * 若立即播放 TTS，服务器会把 TTS 开头误判为用户语音（如"对啦"被识别为"对"）。
     * 延迟 200ms 可让服务器端 VAD 有足够时间处理完用户语音，避免自问自答。
     */
    private fun scheduleAudioFrame(pcm: ShortArray) {
        mainHandler.postDelayed({
            player.enqueue(pcm)
            if (stateMachine.state == ChatState.IDLE) {
                stateMachine.setState(ChatState.AI_SPEAKING)
            }
        }, TTS_PLAY_DELAY_MS)
    }

    /**
     * 二进制音频帧分流（在 OkHttp 回调线程解码）：
     *  - RIFF 魔数：自定义代理下发的 WAV → 直接解析 PCM
     *  - 其它：官方直连的原始 Opus → libopus 解码
     */
    private fun handleAudioFrame(data: ByteArray) {
        if (data.size < 8) return
        audioFrameCount++
        val now = System.currentTimeMillis()
        if (audioFrameCount == 1) {
            firstAudioFrameAt = now
            Log.i(TAG, "[WS] audio frame #1 (${data.size}B), tts start")
        } else {
            val gap = now - lastAudioFrameAt
            if (audioFrameCount % 50 == 0 || gap > 200) {
                val total = now - firstAudioFrameAt
                Log.i(TAG, "[WS] audio frame #$audioFrameCount (${data.size}B), gap=${gap}ms, total=${total}ms")
            }
        }
        lastAudioFrameAt = now
        val pcm = if (WavParser.isWav(data)) {
            val parsed = WavParser.parse(data) ?: return
            // 代理下发的 WAV 采样率可能变化（固定 16kHz），保持播放器同步
            if (parsed.first != decodeSampleRate) {
                decodeSampleRate = parsed.first
                player.setSampleRate(parsed.first)
            }
            parsed.second
        } else {
            // 60ms 帧：按服务器采样率换算采样数（16kHz=960，24kHz=1440）
            opusDecoder?.decode(data, decodeSampleRate * 60 / 1000) ?: return
        }
        if (pcm.isEmpty()) return
        scheduleAudioFrame(pcm)
    }

    // ---------------- 辅助 ----------------

    private fun appendChat(role: ChatRole, content: String) {
        val message = ChatMessage(
            role = role,
            content = content,
            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
        )
        mainHandler.post { onChatMessage?.invoke(message) }
    }

    private fun setConnectionStatus(status: ConnectionStatus) {
        if (connectionStatus == status) return
        connectionStatus = status
        onConnectionStatusChanged?.invoke(status)
    }

    /** 重置状态机状态（WebSocket 断开时调用，避免重连后状态机状态与实际不符） */
    private fun resetStateMachine() {
        stateMachine.reset()
    }

    private companion object {
        const val TAG = "XiaoZhiController"
        const val DEFAULT_SAMPLE_RATE = 16000

        /** tts stop 后等待尾音帧入队的宽限期 */
        const val TTS_STOP_GRACE_MS = 800L

        /** listen stop 后延迟播放 TTS 的时间（避免服务器端 VAD 误判） */
        const val TTS_PLAY_DELAY_MS = 1500L
    }
}
