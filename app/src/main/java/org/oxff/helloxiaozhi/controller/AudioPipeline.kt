package org.oxff.helloxiaozhi.controller

import android.media.AudioManager
import android.os.Handler
import android.util.Log
import org.oxff.helloxiaozhi.audio.AudioPlayer
import org.oxff.helloxiaozhi.audio.AudioRecorderManager
import org.oxff.helloxiaozhi.audio.OpusCodec
import org.oxff.helloxiaozhi.audio.WavParser
import org.oxff.helloxiaozhi.chat.ChatState
import org.oxff.helloxiaozhi.chat.ChatStateMachine
import org.oxff.helloxiaozhi.util.AudioMath

/**
 * 音频管道：负责语音通话的音频链路管理。
 *
 * 职责：
 *  - 管理录音（AudioRecorderManager）与播放（AudioPlayer）
 *  - 管理 Opus 编解码器
 *  - 处理上行音频帧（录音 → 增强 → 编码 → 发送）
 *  - 处理下行音频帧（接收 → 解码 → 播放）
 *  - 协调音频状态与状态机
 *
 * 从 XiaoZhiController 拆分而来，专注于音频链路职责。
 */
class AudioPipeline(
    private val audioManager: AudioManager,
    private val mainHandler: Handler,
    private val stateMachine: ChatStateMachine,
    private val messageDispatcher: MessageDispatcher? = null,
) {
    /** 用户说话电平回调（驱动声浪 UI） */
    var onUserWaveLevel: ((Float) -> Unit)? = null

    /** AI 说话电平回调（驱动声浪 UI） */
    var onAiWaveLevel: ((Float) -> Unit)? = null

    /** 错误回调 */
    var onError: ((String) -> Unit)? = null

    /** 发送音频数据回调 */
    var onSendAudioData: ((ByteArray) -> Unit)? = null

    /** AI 是否正在播放 */
    @Volatile
    var isAiPlaying = false
        private set

    /** AI 结束语回调（用于自动挂断语音通话） */
    var onAiFarewell: (() -> Unit)? = null

    /** 通话会话是否存活 */
    @Volatile
    var inVoiceCall = false
        private set

    /**
     * 是否正在播放本地音乐。
     * 本地音乐播放期间屏蔽服务器下发的 TTS 音频帧，避免服务器 AI 抢播它平台的歌
     * 与本地音乐混音（用户"点歌却听到 AI 平台歌曲"的问题来源）。
     */
    @Volatile
    var isLocalMusicPlaying = false

    /**
     * 上行就绪时间戳（SystemClock.uptimeMillis）：进入通话后给 listen start
     * 一个到达服务器并激活服务器端 VAD 的缓冲窗口，窗口期内的上行帧会被丢弃。
     *
     * 背景：进入通话时录音与 listen start 几乎同时发出，但 listen start 到达
     * 服务器、服务器 VAD 激活存在网络往返延迟。若用户在接通动画期间就开始说话，
     * 这段窗口内的前半句音频虽上行了，服务器却还没开始监听，导致只识别到后半句。
     */
    @Volatile
    private var uplinkReadyAtMs = 0L

    /**
     * 服务器回复抑制截止时间戳（SystemClock.uptimeMillis）。
     *
     * 本地音乐控制指令（"别唱了"/"下一首"）执行后会发 AbortMessage 取消服务器
     * 回复，但停止类指令随即使 [isLocalMusicPlaying] 复位为 false，那道 TTS 门控
     * 跟着失效；abort 到达服务器前已在途的 llm/tts 仍会被播出来，形成
     * "AI 接着控制指令闲聊" 的割裂体验（真机实测：说 "别唱了" 后 AI 围绕这句
     * 聊了下去）。该窗口在指令处理后继续丢弃服务器文本与音频，直到用户
     * 开始下一轮真实对话（[clearServerReplySuppress]）或窗口超时自然失效。
     */
    @Volatile
    private var suppressReplyUntilMs = 0L

    /**
     * 开启服务器回复抑制窗口（本地音乐控制指令已处理后调用）。
     */
    fun suppressServerReply(windowMs: Long = SERVER_REPLY_SUPPRESS_MS) {
        suppressReplyUntilMs = android.os.SystemClock.uptimeMillis() + windowMs
        Log.i(TAG, "server reply suppressed for ${windowMs}ms")
    }

    /** 清除服务器回复抑制（用户开始新一轮真实对话时调用） */
    fun clearServerReplySuppress() {
        if (suppressReplyUntilMs != 0L) {
            suppressReplyUntilMs = 0L
            Log.i(TAG, "server reply suppress cleared")
        }
    }

    /** 当前是否处于服务器回复抑制窗口内 */
    fun isServerReplySuppressed(): Boolean =
        android.os.SystemClock.uptimeMillis() < suppressReplyUntilMs

    private var recorder: AudioRecorderManager? = null
    private var opusEncoder: OpusCodec? = null
    private var opusDecoder: OpusCodec? = null
    private var decodeSampleRate = DEFAULT_SAMPLE_RATE

    private val player = AudioPlayer()

    init {
        // 播放队列播空 → 回到 IDLE，并检查是否有待处理的结束语
        player.onQueueEmpty = {
            mainHandler.post {
                isAiPlaying = false
                if (stateMachine.state == ChatState.AI_SPEAKING) {
                    stateMachine.setState(ChatState.IDLE)
                }
                // AI 语音播放完成，检查是否有待处理的结束语
                if (messageDispatcher?.consumePendingFarewell() == true) {
                    Log.i(TAG, "AI 语音播放完成，触发结束语回调")
                    onAiFarewell?.invoke()
                }
            }
        }
        // 播放会话常驻（聊天模式的 TTS 回复也需要播放）
        player.startSession()
    }

    /**
     * 开始语音通话
     */
    fun startVoiceCall(sessionId: String) {
        Log.i(TAG, "startVoiceCall: state=${stateMachine.state}, sessionId=$sessionId")
        inVoiceCall = true
        // 上行就绪窗口：给 listen start 留出到达服务器并激活 VAD 的时间，
        // 避免进入通话瞬间用户说的前半句被服务器漏识别
        uplinkReadyAtMs = android.os.SystemClock.uptimeMillis() + UPLINK_READY_DELAY_MS
        player.pausePlayback()
        if (stateMachine.state != ChatState.IDLE) {
            stateMachine.setState(ChatState.IDLE)
        }

        val encoder = opusEncoder ?: OpusCodec.encoder().also { opusEncoder = it }
        recorder = AudioRecorderManager(
            onFrame = { frame, level ->
                // 上行就绪窗口期内不处理（但电平仍驱动 UI）。
                //
                // 注意：此处**不能**用 isAiPlaying 门控。AI 播放期间若不把帧送给
                // 状态机，则：① 状态机无法做本地打断检测；② 音频也不上行，
                // 服务器端 VAD 收不到任何声音而永远不下发 stt，形成“等 stt
                // 才能打断、但不打断就永远收不到 stt”的双重死锁。
                // 是否上行由状态机内部按 state 决定（AI_SPEAKING 不上行，
                // 避免 TTS 泄漏污染服务器端 VAD）。
                if (android.os.SystemClock.uptimeMillis() >= uplinkReadyAtMs) {
                    stateMachine.handleAudioLevel(level, frame)
                }
            },
            onError = { message ->
                mainHandler.post { onError?.invoke(message) }
            },
            audioManager = audioManager,
        ).also { it.start() }
    }

    /**
     * 停止语音通话
     */
    fun stopVoiceCall() {
        inVoiceCall = false
        isAiPlaying = false
        recorder?.stop()
        recorder = null
        player.pausePlayback()
        stateMachine.reset()
    }

    /**
     * 本地音乐播放开始时强制状态机回到 IDLE。
     *
     * 两个必要原因：
     *  1. MusicPlayer 用 MediaPlayer 播放，其声音**不在 AudioTrack 的 AEC 参考
     *     信号内**，会被麦克风完整录入且电平远超打断阈值；若状态机停在
     *     AI_SPEAKING，本地打断检测会被音乐声持续误触发。
     *  2. 必须走 IDLE 分支持续上行音频，服务器端 VAD 才能识别用户的唤醒词
     *     打断指令（音乐播放期间的打断依赖 STT 文本唤醒词前缀匹配）。
     *
     * 从 AI_SPEAKING 迁移会触发 AI_STOP_SPEAKING → XiaoZhiController 重发
     * listen start，正好开启新一轮监听。
     */
    fun enterLocalMusicMode() {
        isAiPlaying = false
        if (stateMachine.state != ChatState.IDLE) {
            Log.i(TAG, "local music start: force state ${stateMachine.state} -> IDLE")
            stateMachine.setState(ChatState.IDLE)
        }
    }

    /**
     * 设置播放增益（AI 音量）
     */
    fun setPlaybackGain(gain: Float) {
        player.playbackGain = gain
    }

    /**
     * 设置麦克风增益（用户音量）
     */
    fun setMicGainDb(db: Float) {
        recorder?.micGainDb = db
    }

    /**
     * 处理接收到的音频帧
     */
    fun handleAudioFrame(data: ByteArray) {
        if (data.size < 8) return
        val pcm = if (WavParser.isWav(data)) {
            val parsed = WavParser.parse(data) ?: return
            if (parsed.first != decodeSampleRate) {
                decodeSampleRate = parsed.first
                player.setSampleRate(parsed.first)
            }
            parsed.second
        } else {
            opusDecoder?.decode(data, decodeSampleRate * 60 / 1000) ?: return
        }
        if (pcm.isEmpty()) return
        scheduleAudioFrame(pcm)
    }

    /**
     * 处理 Hello 消息（重建解码器）
     */
    fun onHelloReceived(sampleRate: Int) {
        if (sampleRate != decodeSampleRate) {
            decodeSampleRate = sampleRate
            opusDecoder?.close()
            opusDecoder = OpusCodec.decoder(sampleRate)
            player.setSampleRate(sampleRate)
        }
    }

    /**
     * 处理 TTS 开始
     */
    fun onTtsStart() {
        mainHandler.post {
            if (!inVoiceCall) {
                Log.i(TAG, "tts start dropped (not in voice call)")
                return@post
            }
            // 本地音乐播放期间屏蔽服务器 TTS，避免服务器 AI 抢播它平台的歌与本地音乐混音
            if (isLocalMusicPlaying) {
                Log.i(TAG, "tts start dropped (local music playing)")
                return@post
            }
            // 本地音乐控制指令后的抑制窗口：丢弃 abort 在途的服务器回复
            if (isServerReplySuppressed()) {
                Log.i(TAG, "tts start dropped (server reply suppressed)")
                return@post
            }
            isAiPlaying = true
            if (stateMachine.state == ChatState.IDLE || stateMachine.state == ChatState.USER_SPEAKING) {
                stateMachine.setState(ChatState.AI_SPEAKING)
            }
        }
    }

    /**
     * 处理 TTS 停止
     */
    fun onTtsStop() {
        mainHandler.post {
            if (player.isQueueEmpty()) {
                isAiPlaying = false
                if (stateMachine.state == ChatState.AI_SPEAKING) {
                    stateMachine.setState(ChatState.IDLE)
                }
            } else {
                mainHandler.postDelayed({
                    isAiPlaying = false
                    if (stateMachine.state == ChatState.AI_SPEAKING && player.isQueueEmpty()) {
                        stateMachine.setState(ChatState.IDLE)
                    }
                }, TTS_STOP_GRACE_MS)
            }
        }
    }

    /**
     * 暂停播放（用户开口时）
     */
    fun pausePlayback() {
        player.pausePlayback()
    }

    /**
     * 复位 AI 播放标记（用户打断 AI 时调用）。
     *
     * 打断后 TTS 队列已被 pausePlayback 清空，onQueueEmpty 不会再触发
     * （pausePlayback 置 playing=false，播放循环走等待分支），因此必须显式
     * 复位，否则 isAiPlaying 会持续为 true 让机器人表情映射等下游逻辑误判。
     */
    fun clearAiPlaying() {
        isAiPlaying = false
    }

    /**
     * 恢复播放（AI 开始说话时）
     */
    fun resumePlayback() {
        mainHandler.postDelayed({
            if (inVoiceCall) player.resumePlayback()
        }, TTS_PLAY_DELAY_MS)
    }

    /**
     * 发送音频帧（由状态机调用）
     */
    fun sendAudioFrame(frame: ShortArray) {
        opusEncoder?.encode(frame)?.let { onSendAudioData?.invoke(it) }
    }

    /**
     * 清理资源
     */
    fun shutdown() {
        recorder?.stop()
        recorder = null
        player.stopSession()
        opusEncoder?.close()
        opusEncoder = null
        opusDecoder?.close()
        opusDecoder = null
    }

    /**
     * 延迟播放 TTS 音频帧
     */
    private fun scheduleAudioFrame(pcm: ShortArray) {
        val aiLevel = AudioMath.rmsLevel(pcm)
        mainHandler.postDelayed({
            if (!inVoiceCall) {
                Log.i(TAG, "audio frame dropped (not in voice call)")
                return@postDelayed
            }
            // 本地音乐播放期间屏蔽服务器 TTS 音频帧，避免混音
            if (isLocalMusicPlaying) {
                Log.i(TAG, "audio frame dropped (local music playing)")
                return@postDelayed
            }
            // 本地音乐控制指令后的抑制窗口：丢弃 abort 在途的 TTS 音频
            if (isServerReplySuppressed()) {
                Log.i(TAG, "audio frame dropped (server reply suppressed)")
                return@postDelayed
            }
            player.enqueue(pcm)
            onAiWaveLevel?.invoke(aiLevel)
            if (stateMachine.state == ChatState.IDLE) {
                stateMachine.setState(ChatState.AI_SPEAKING)
            }
        }, TTS_PLAY_DELAY_MS)
    }

    private companion object {
        const val TAG = "AudioPipeline"
        const val DEFAULT_SAMPLE_RATE = 16000
        const val TTS_STOP_GRACE_MS = 200L
        const val TTS_PLAY_DELAY_MS = 300L

        /**
         * 本地音乐控制指令后的服务器回复抑制窗口（毫秒）。
         *
         * 本窗口需要覆盖两类场景：
         *  1. abort 在途回复：服务器已开始生成、但被 AbortMessage 打断后仍会发完的小段
         *     （如「好啦好啦，不唱了哟」）。仅需网络往返 200~400ms + 在途帧排空。
         *  2. 同批语音的 STT 尾巴：主机 VAD 可能把一句「阿妹阿妹，别唱了」切出第二条
         *     STT（真机实测「这。」），服务器会把这条尾巴当作**新的一轮输入**开启完整
         *     回复，而非停止。此类回复通常持续 5~15s，太长会漏播后半句（真机实测底部
         *     「你其实想让我放点别的歌？」仍被播出）。
         *
         * 取值须覆盖第 2 类的最长一轮回复。过长仅对「窗口内不带唤醒词的对话」有影响
         * （会被忽略）；而用户真实新对话通常以唤醒词开头，走 handleSttText 主动清除
         * 抑制，不受窗口限制。
         */
        const val SERVER_REPLY_SUPPRESS_MS = 12000L

        /**
         * 进入通话后的上行就绪延迟（毫秒）：给 listen start 留出到达服务器并激活
         * 服务器端 VAD 的时间，同时覆盖接通动画期间用户可能开始说话的窗口。
         * 取值需覆盖：网络往返（200~400ms）+ 服务器处理（200ms）+ 用户可能在
         * 接通动画期间就开始说话的提前量（约 1s）。
         */
        const val UPLINK_READY_DELAY_MS = 1500L
    }
}
