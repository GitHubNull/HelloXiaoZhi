package org.oxff.helloxiaozhi.chat

import org.oxff.helloxiaozhi.util.SilenceScheduler
import org.oxff.helloxiaozhi.util.UiExecutor

/**
 * 语音通话状态机（服务器端 VAD 驱动 + 客户端本地打断检测）。
 *
 * 状态与转换：
 *  - IDLE：连接建立后自动开始监听，所有音频帧直接上行（服务器端 VAD 检测）
 *  - USER_SPEAKING：服务器端 VAD 检测到用户说话（stt 消息触发），持续上行
 *  - AI_SPEAKING：服务器发送 TTS start，AI 播放中**不上行**（避免 TTS 泄漏
 *    污染服务器端 VAD），但持续做本地电平打断检测（barge-in）
 *
 * 为何打断必须由客户端检测（真机实测结论）：
 *  AI 播放期间不上行 → 服务器端 VAD 收不到任何音频 → 永远不会下发 stt
 *  消息 → 任何“收到 stt 再打断”的逻辑构成死锁。因此打断只能靠本地
 *  电平检测：VOICE_COMMUNICATION + 硬件 AEC 下，AI 播放期间麦克风残留回声
 *  电平实测仅 0.011~0.06，远低于 [THRESHOLD_INTERRUPT]=0.1，配合连续
 *  [REQUIRED_INTERRUPT_FRAMES]=3 帧（180ms）防抖可可靠区分真实用户开口
 *  与回声瞬时尖峰。
 *
 * 消息副作用：
 *  - 进入 USER_SPEAKING：触发 ChatEvent.USER_START_SPEAKING（暂停播放）
 *  - 离开 USER_SPEAKING：触发 ChatEvent.USER_STOP_SPEAKING
 *  - 进入 AI_SPEAKING：触发 ChatEvent.AI_START_SPEAKING
 *  - 离开 AI_SPEAKING：触发 ChatEvent.AI_STOP_SPEAKING
 *  - 本地打断确认时：补发预触发缓冲帧 + 发送 AbortMessage
 *
 * listen start/stop 不由状态迁移发送：mode=auto 下服务器收到一次 listen start 后
 * 持续监听整个通话，由 XiaoZhiController 在通话开始/结束时发送。
 * 若收到 stt 后再发 listen start 会重置服务器监听（真机实测死锁：服务器等
 * listen start 才处理音频，而旧逻辑等 stt 才发 listen start）。
 *
 * 线程模型：所有状态迁移通过 [uiExecutor] 串行化（生产环境为主线程）。
 */
class ChatStateMachine(
    private val uiExecutor: UiExecutor,
    private val scheduler: SilenceScheduler,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** 发送一帧音频数据（IDLE / USER_SPEAKING 期间每 60ms 一次） */
        fun sendAudioData(frame: ShortArray)

        /** 发送 JSON 文本消息（listen start/stop、abort） */
        fun sendTextData(message: Any)

        /** 获取当前会话 ID（abort 消息需要） */
        fun getSessionId(): String

        /** 状态事件 */
        fun onEvent(event: ChatEvent)

        /** 用户持续说话时的电平（驱动声浪 UI）；可能为空 */
        fun onUserWaveLevel(level: Float) = Unit
    }

    @Volatile
    var state: ChatState = ChatState.IDLE
        private set

    /** 连续超过打断阈值的帧计数（AI_SPEAKING 打断防抖） */
    private var consecutiveInterruptFrames = 0

    /**
     * AI_SPEAKING 期间的预触发环形缓冲。
     *
     * 打断确认需连续 3 帧（180ms），这几帧 onset 前导音频若不缓存会被丢弃，
     * 导致服务器只识别到后半句（真机实测：“那我有个问题”只识别到“下我有个问题”）。
     * AEC 下 AI 播放期间残留电平 0.011~0.06，远低于打断阈值，补发安全。
     */
    private val preRollBuffer = ArrayDeque<ShortArray>()

    /**
     * 状态迁移日志钩子（生产环境注入 android.util.Log，单元测试默认 no-op）。
     * 不直接用 android.util.Log：JVM 单元测试环境未 mock 会抛异常。
     */
    var logger: ((String) -> Unit)? = null

    /**
     * 状态真正切换后的通知（在 uiExecutor 线程触发，同态 setState 不触发）。
     * 通话页的星河双球动画由它驱动。
     */
    var onStateChanged: ((ChatState) -> Unit)? = null

    /** 处理一帧音频电平（可在任意线程调用，内部切换到 uiExecutor） */
    fun handleAudioLevel(level: Float, frame: ShortArray) {
        uiExecutor.post { dispatch(level, frame) }
    }

    /** 强制切换到指定状态（非主线程调用时自动切换线程） */
    fun setState(newState: ChatState) {
        uiExecutor.post { transition(newState) }
    }

    /** 重置状态机（进入语音通话时调用） */
    fun reset() {
        consecutiveInterruptFrames = 0
        preRollBuffer.clear()
        setState(ChatState.IDLE)
    }

    /** 销毁：取消挂起的静音计时 */
    fun destroy() {
        // 无静音计时需要取消（服务器端 VAD 驱动，无客户端静音检测）
    }

    private fun dispatch(level: Float, frame: ShortArray) {
        when (state) {
            ChatState.IDLE -> {
                // IDLE 状态：直接发送所有帧（服务器端 VAD 检测用户说话）
                // 对齐参考 APP auto 模式：连接后自动开始监听，无客户端 VAD
                callbacks.sendAudioData(frame)
            }

            ChatState.USER_SPEAKING -> {
                // USER_SPEAKING 状态：直接发送所有帧
                callbacks.sendAudioData(frame)
                callbacks.onUserWaveLevel(level)
            }

            ChatState.AI_SPEAKING -> {
                // AI 播放期间不上行（避免 TTS 泄漏污染服务器端 VAD），
                // 但必须缓存进预触发缓冲：打断确认的 3 帧 onset 前导音频不缓存
                // 会导致服务器只识别到打断句的后半句
                pushPreRoll(frame)
                // 客户端本地打断检测（barge-in）：服务器端 VAD 在 AI 播放期间
                // 收不到音频，无法下发 stt，打断只能靠本地电平判定
                if (level > THRESHOLD_INTERRUPT) {
                    consecutiveInterruptFrames++
                    if (consecutiveInterruptFrames >= REQUIRED_INTERRUPT_FRAMES) {
                        consecutiveInterruptFrames = 0
                        logger?.invoke(
                            "barge-in detected: level=$level, buffered ${preRollBuffer.size} pre-roll frames"
                        )
                        // 先 abort 打断服务器 TTS 生成；句首缓冲帧在 transition
                        // 里、listen start 之后补发（服务器必须先收到 listen start
                        // 才会处理上行音频，否则补发的句首会被丢弃）
                        callbacks.sendTextData(
                            AbortMessage(sessionId = callbacks.getSessionId())
                        )
                        transition(ChatState.USER_SPEAKING)
                    }
                } else {
                    consecutiveInterruptFrames = 0
                }
            }
        }
    }

    /** 将一帧存入预触发缓冲（超出容量时丢弃最旧帧） */
    private fun pushPreRoll(frame: ShortArray) {
        while (preRollBuffer.size >= PRE_ROLL_INTERRUPT_FRAMES) {
            preRollBuffer.removeFirst()
        }
        preRollBuffer.addLast(frame.copyOf())
    }

    /** 补发预触发缓冲帧（打断确认时调用，保证句首音频不丢） */
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
                // 补发预触发缓冲帧（打断句首音频）。必须在离开的
                // AI_STOP_SPEAKING 事件之后：该事件会触发 XiaoZhiController 重发
                // listen start，而服务器必须先收到 listen start 才会处理上行音频，
                // 先补发会被丢弃（真机实测：listen 生命周期死锁教训）。
                flushPreRoll()
                callbacks.onEvent(ChatEvent.USER_START_SPEAKING)
            }
            ChatState.AI_SPEAKING -> callbacks.onEvent(ChatEvent.AI_START_SPEAKING)
        }
        onStateChanged?.invoke(newState)
    }

    private fun exitUserSpeaking() {
        callbacks.onEvent(ChatEvent.USER_STOP_SPEAKING)
    }

    private companion object {
        /**
         * 用户打断 AI 的音频电平阈值。
         *
         * 取值依据（真机实测）：VOICE_COMMUNICATION + 硬件 AEC 下，AI 播放期间
         * 麦克风残留回声电平仅 0.011~0.06，远低于 0.1；真实用户开口电平通常
         * > 0.1，因此 0.1 可区分两者且不会被 TTS 回声误触发。
         */
        const val THRESHOLD_INTERRUPT = 0.1f

        /** 打断 AI 需连续确认的帧数（约 180ms，过滤回声瞬时尖峰） */
        const val REQUIRED_INTERRUPT_FRAMES = 3

        /**
         * AI_SPEAKING 期间预触发缓冲容量（约 960ms @ 60ms/帧）。
         *
         * 打断场景下用户语音被 AEC 部分消除、电平较低，句首轻声可持续
         * 600ms+；10 帧（600ms）会导致句首最旧帧被挤出缓冲（“今天要上班吗”
         * 只识别到“要上班吗”），16 帧（960ms）可覆盖绝大多数中文句首轻声场景。
         */
        const val PRE_ROLL_INTERRUPT_FRAMES = 16
    }
}
