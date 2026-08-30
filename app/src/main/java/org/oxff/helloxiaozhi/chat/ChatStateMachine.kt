package org.oxff.helloxiaozhi.chat

import org.oxff.helloxiaozhi.util.SilenceScheduler
import org.oxff.helloxiaozhi.util.UiExecutor

/**
 * 语音通话状态机（对齐参考 APP auto 模式：服务器端 VAD 驱动）。
 *
 * 状态与转换：
 *  - IDLE：连接建立后自动开始监听，所有音频帧直接上行（服务器端 VAD 检测）
 *  - USER_SPEAKING：服务器端 VAD 检测到用户说话（stt 消息触发），持续上行
 *  - AI_SPEAKING：服务器发送 TTS start，AI 播放中完全不上行（由 XiaoZhiController
 *    的 isAiPlaying 标志控制）
 *
 * 与参考 APP 的关键对齐点：
 *  - 无客户端 VAD 阈值检测（THRESHOLD_SPEAKING / THRESHOLD_INTERRUPT 已移除）
 *  - 无预触发缓冲（PRE_ROLL_FRAMES / PRE_ROLL_INTERRUPT_FRAMES 已移除）
 *  - 无客户端打断检测（REQUIRED_INTERRUPT_FRAMES 已移除）
 *  - AI 播放时完全不上行（避免 TTS 泄漏污染服务器端 VAD）
 *
 * 消息副作用：
 *  - 进入 USER_SPEAKING：触发 ChatEvent.USER_START_SPEAKING（暂停播放）
 *  - 离开 USER_SPEAKING：触发 ChatEvent.USER_STOP_SPEAKING
 *  - 进入 AI_SPEAKING：触发 ChatEvent.AI_START_SPEAKING
 *  - 离开 AI_SPEAKING：触发 ChatEvent.AI_STOP_SPEAKING
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
                // AI_SPEAKING 状态：完全不上行（由 XiaoZhiController 的 isAiPlaying 控制）
                // 对齐参考 APP：AI 播放时（o.f215p = true）完全停止上行
            }
        }
    }

    private fun transition(newState: ChatState) {
        if (state == newState) return
        logger?.invoke("state: $state -> $newState")
        when (state) {
            ChatState.USER_SPEAKING -> exitUserSpeaking()
            ChatState.AI_SPEAKING -> callbacks.onEvent(ChatEvent.AI_STOP_SPEAKING)
            ChatState.IDLE -> Unit
        }
        state = newState
        when (newState) {
            ChatState.IDLE -> Unit
            ChatState.USER_SPEAKING -> callbacks.onEvent(ChatEvent.USER_START_SPEAKING)
            ChatState.AI_SPEAKING -> callbacks.onEvent(ChatEvent.AI_START_SPEAKING)
        }
        onStateChanged?.invoke(newState)
    }

    private fun exitUserSpeaking() {
        callbacks.onEvent(ChatEvent.USER_STOP_SPEAKING)
    }
}
