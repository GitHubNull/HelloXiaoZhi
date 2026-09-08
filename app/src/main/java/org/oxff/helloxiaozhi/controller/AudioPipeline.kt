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
                // AI 播放时完全不上行；上行就绪窗口期内也不上行（但电平仍驱动 UI）
                if (!isAiPlaying && android.os.SystemClock.uptimeMillis() >= uplinkReadyAtMs) {
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
         * 进入通话后的上行就绪延迟（毫秒）：给 listen start 留出到达服务器并激活
         * 服务器端 VAD 的时间，同时覆盖接通动画期间用户可能开始说话的窗口。
         * 取值需覆盖：网络往返（200~400ms）+ 服务器处理（200ms）+ 用户可能在
         * 接通动画期间就开始说话的提前量（约 1s）。
         */
        const val UPLINK_READY_DELAY_MS = 1500L
    }
}
