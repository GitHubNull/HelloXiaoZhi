package org.oxff.helloxiaozhi.chat

import org.oxff.helloxiaozhi.util.SilenceScheduler
import org.oxff.helloxiaozhi.util.UiExecutor

/**
 * 语音通话状态机，逐行移植 ref 前端 ChatStateManager.ts。
 *
 * 状态与转换（阈值与时长与 Web 端 App.vue 配置一致）：
 *  - IDLE：电平 > 0.04 连续 3 帧 → USER_SPEAKING；IDLE 期间音频帧进入
 *    预触发环形缓冲（pre-roll），触发时先补发缓冲帧再发 listen start，
 *    避免句首丢失（对齐 ESP32 固件"listen 前音频流已建立"的行为）
 *  - USER_SPEAKING：持续发送音频帧；电平 < 0.04 连续 5 帧启动静音计时
 *    （1000ms），计时到期 → AI_SPEAKING；恢复说话则取消计时
 *  - AI_SPEAKING：电平 > 0.1 连续 3 帧视为用户打断 → 发送 abort →
 *    USER_SPEAKING；打断确认期间的帧由预触发缓冲补发，保证打断句首完整
 *
 * 消息副作用：
 *  - 进入 USER_SPEAKING：补发预触发缓冲帧 → 发送 listen start（携带 session_id）
 *  - 离开 USER_SPEAKING：取消静音计时 + 发送 listen stop（携带 session_id）
 *  - 打断 AI：发送 abort（携带 session_id）
 *
 * 线程模型：所有状态迁移通过 [uiExecutor] 串行化（生产环境为主线程）。
 */
class ChatStateMachine(
    private val uiExecutor: UiExecutor,
    private val scheduler: SilenceScheduler,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** 发送一帧音频数据（USER_SPEAKING 期间每 60ms 一次） */
        fun sendAudioData(frame: ShortArray)

        /** 发送 JSON 文本消息（listen start/stop、abort） */
        fun sendTextData(message: Any)

        /** 获取当前会话 ID（abort 消息需要） */
        fun getSessionId(): String

        /** 状态事件（对应 Web 端 ChatEvent） */
        fun onEvent(event: ChatEvent)

        /** 用户持续说话时的电平（驱动声浪 UI）；可能为空 */
        fun onUserWaveLevel(level: Float) = Unit
    }

    companion object {
        /**
         * 判定用户开始说话的音频电平阈值（App.vue USER_SPEAKING）。
         *
         * 取值依据：中文句首轻声（如"你"、"今"、"晚"）电平常在 0.02~0.09 之间，
         * 原阈值 0.04 会导致 VAD 防抖计数器反复重置（电平在阈值边缘徘徊），
         * 句首轻声段被挤出预触发缓冲，服务器只收到后半句（如"你猜"只识别到"猜"）。
         * 降为 0.02 可让句首轻声段更容易触发，同时保持 3 帧防抖过滤噪音。
         */
        const val THRESHOLD_SPEAKING = 0.02f

        /** 用户打断 AI 的音频电平阈值（App.vue USER_INTERRUPT_AI） */
        const val THRESHOLD_INTERRUPT = 0.1f

        /** 用户停止说话后进入 AI 回答的静音时长（App.vue SILENCE） */
        const val SILENCE_MS = 1000L

        /** 进入 USER_SPEAKING 需连续确认的帧数（约 180ms，参考 FSMN-VAD sil_to_speech_time_thres） */
        const val REQUIRED_SPEAKING_FRAMES = 3

        /** 退出 USER_SPEAKING 需连续确认的帧数（约 300ms，参考 FSMN-VAD speech_to_sil_time_thres） */
        const val REQUIRED_SILENCE_FRAMES = 5

        /** 打断 AI 需连续确认的帧数（约 180ms，过滤回声瞬时尖峰） */
        const val REQUIRED_INTERRUPT_FRAMES = 3

        /**
         * AI_SPEAKING 期间预触发缓冲容量（约 960ms @ 60ms/帧）。
         *
         * 取值依据：打断场景下用户语音被 AEC 部分消除，电平较低，句首轻声段
         * 可达 600ms+；4 帧（240ms）会导致打断句首丢失（如"对，你吃了吗"只
         * 识别到"你吃了吗"）。与 IDLE 场景保持一致（16 帧），确保打断句首完整。
         */
        const val PRE_ROLL_INTERRUPT_FRAMES = 16

        /**
         * 预触发环形缓冲容量（约 960ms @ 60ms/帧）。
         * IDLE 期间缓存最近若干帧，检测到说话时随 listen start 一并补发，
         * 覆盖"开口 → VAD 防抖确认 → 状态切换"期间的句首音频空洞。
         *
         * 取值依据：中文句首轻声（如"今天"、"你好"）电平常低于 VAD 阈值，
         * 实测句首轻声段可达 600ms（10 帧）；8 帧（480ms）会导致句首最旧帧
         * 被挤出缓冲，服务器只收到后半句（如"今天要上班吗"只识别到"要上班吗"）。
         * 16 帧（960ms）可覆盖绝大多数中文句首轻声场景。
         */
        const val PRE_ROLL_FRAMES = 16
    }

    @Volatile
    var state: ChatState = ChatState.IDLE
        private set

    private var silencePending = false

    /** 连续超过说话阈值的帧计数（进入 USER_SPEAKING 防抖） */
    private var consecutiveSpeakingFrames = 0

    /** 连续低于说话阈值的帧计数（退出 USER_SPEAKING 防抖） */
    private var consecutiveSilenceFrames = 0

    /** 连续超过打断阈值的帧计数（AI_SPEAKING 打断防抖） */
    private var consecutiveInterruptFrames = 0

    /**
     * 预触发环形缓冲：IDLE / AI_SPEAKING 期间缓存最近若干帧，
     * 进入 USER_SPEAKING 时按序补发，保证句首音频完整到达服务器。
     * 帧必须 copy（录音线程会复用同一数组）。
     */
    private val preRollBuffer = ArrayDeque<ShortArray>()

    /**
     * 状态迁移日志钩子（生产环境注入 android.util.Log，单元测试默认 no-op）。
     * 不直接用 android.util.Log：JVM 单元测试环境未 mock 会抛异常。
     */
    var logger: ((String) -> Unit)? = null

    /** 处理一帧音频电平（可在任意线程调用，内部切换到 uiExecutor） */
    fun handleAudioLevel(level: Float, frame: ShortArray) {
        uiExecutor.post { dispatch(level, frame) }
    }

    /** 强制切换到指定状态（非主线程调用时自动切换线程） */
    fun setState(newState: ChatState) {
        uiExecutor.post { transition(newState) }
    }

    /** 重置状态机（进入语音通话时调用，对应 Web 端 showVoiceCallPanel） */
    fun reset() {
        setState(ChatState.IDLE)
    }

    /** 销毁：取消挂起的静音计时（对应 Web 端 destroy） */
    fun destroy() {
        cancelSilence()
    }

    private fun dispatch(level: Float, frame: ShortArray) {
        when (state) {
            ChatState.IDLE -> {
                // IDLE 期间不直接发送，但缓存到预触发缓冲：
                // 触发说话时补发，避免句首 180ms+ 的音频丢失
                pushPreRoll(frame)
                if (level > THRESHOLD_SPEAKING) {
                    consecutiveSpeakingFrames++
                    if (consecutiveSpeakingFrames >= REQUIRED_SPEAKING_FRAMES) {
                        consecutiveSpeakingFrames = 0
                        transition(ChatState.USER_SPEAKING)
                    }
                } else {
                    consecutiveSpeakingFrames = 0
                }
            }

            ChatState.USER_SPEAKING -> {
                callbacks.sendAudioData(frame)
                if (level < THRESHOLD_SPEAKING) {
                    consecutiveSilenceFrames++
                    if (!silencePending && consecutiveSilenceFrames >= REQUIRED_SILENCE_FRAMES) {
                        silencePending = true
                        scheduler.schedule(SILENCE_MS) {
                            silencePending = false
                            consecutiveSilenceFrames = 0
                            transition(ChatState.AI_SPEAKING)
                        }
                    }
                } else {
                    consecutiveSilenceFrames = 0
                    cancelSilence()
                    callbacks.onUserWaveLevel(level)
                }
            }

            ChatState.AI_SPEAKING -> {
                // AI 说话期间停止上行音频帧：避免服务器端 VAD 把 AI 播放的 TTS
                // （通过麦克风捕获）误判为用户语音，导致自问自答（如 AI 说"对啦"
                // 被识别为用户说"对"）。用户打断时通过 listen start 重新建立音频流。
                // 注意：AI_SPEAKING 期间不缓存帧到预触发缓冲，因为缓存的帧包含
                // AEC 残留的 TTS 声音，补发会导致 STT 识别错误（如"只加味精和盐"
                // 被识别为"指甲味经和炎"）
                // 同时停止电平检测：避免环境噪音（如空调外机）触发误打断
                if (level > THRESHOLD_INTERRUPT) {
                    consecutiveInterruptFrames++
                    if (consecutiveInterruptFrames >= REQUIRED_INTERRUPT_FRAMES) {
                        consecutiveInterruptFrames = 0
                        callbacks.sendTextData(AbortMessage(sessionId = callbacks.getSessionId()))
                        transition(ChatState.USER_SPEAKING)
                    }
                } else {
                    consecutiveInterruptFrames = 0
                }
            }
        }
    }

    /** 将一帧存入预触发缓冲（超出容量时丢弃最旧帧） */
    private fun pushPreRoll(frame: ShortArray, capacity: Int = PRE_ROLL_FRAMES) {
        while (preRollBuffer.size >= capacity) {
            preRollBuffer.removeFirst()
        }
        preRollBuffer.addLast(frame.copyOf())
    }

    /** 补发预触发缓冲中的全部帧并清空（进入 USER_SPEAKING 时调用） */
    private fun flushPreRoll() {
        val count = preRollBuffer.size
        if (count > 0) {
            logger?.invoke("flushPreRoll: 补发 $count 帧句首音频")
        }
        while (preRollBuffer.isNotEmpty()) {
            callbacks.sendAudioData(preRollBuffer.removeFirst())
        }
    }

    private fun transition(newState: ChatState) {
        if (state == newState) return
        logger?.invoke("state: $state -> $newState")
        consecutiveInterruptFrames = 0
        when (state) {
            ChatState.USER_SPEAKING -> exitUserSpeaking()
            ChatState.AI_SPEAKING -> callbacks.onEvent(ChatEvent.AI_STOP_SPEAKING)
            ChatState.IDLE -> Unit
        }
        state = newState
        when (newState) {
            ChatState.IDLE -> Unit
            ChatState.USER_SPEAKING -> {
                // 先补发预触发缓冲帧（句首音频），再发 listen start：
                // 保证 listen start 到达服务器时音频流已建立且句首完整
                flushPreRoll()
                callbacks.sendTextData(ListenMessage.start(callbacks.getSessionId()))
                callbacks.onEvent(ChatEvent.USER_START_SPEAKING)
            }
            ChatState.AI_SPEAKING -> callbacks.onEvent(ChatEvent.AI_START_SPEAKING)
        }
    }

    private fun exitUserSpeaking() {
        cancelSilence()
        callbacks.sendTextData(ListenMessage.stop(callbacks.getSessionId()))
        callbacks.onEvent(ChatEvent.USER_STOP_SPEAKING)
    }

    private fun cancelSilence() {
        if (silencePending) {
            scheduler.cancel()
            silencePending = false
        }
    }
}
