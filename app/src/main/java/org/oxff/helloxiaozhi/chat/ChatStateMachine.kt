package org.oxff.helloxiaozhi.chat

import org.oxff.helloxiaozhi.util.SilenceScheduler
import org.oxff.helloxiaozhi.util.UiExecutor

/**
 * 语音通话状态机，逐行移植 ref 前端 ChatStateManager.ts。
 *
 * 状态与转换（阈值与时长与 Web 端 App.vue 配置一致）：
 *  - IDLE：电平 > 0.04 → USER_SPEAKING（触发帧不发送，与 Web 端一致）
 *  - USER_SPEAKING：持续发送音频帧；电平 < 0.04 启动静音计时（1000ms），
 *    计时到期 → AI_SPEAKING；恢复说话则取消计时
 *  - AI_SPEAKING：电平 > 0.1 视为用户打断 → 发送 abort → USER_SPEAKING
 *
 * 消息副作用：
 *  - 进入 USER_SPEAKING：发送 listen start（mode=auto）
 *  - 离开 USER_SPEAKING：取消静音计时 + 发送 listen stop
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
        /** 判定用户开始说话的音频电平阈值（App.vue USER_SPEAKING） */
        const val THRESHOLD_SPEAKING = 0.04f

        /** 用户打断 AI 的音频电平阈值（App.vue USER_INTERRUPT_AI） */
        const val THRESHOLD_INTERRUPT = 0.1f

        /** 用户停止说话后进入 AI 回答的静音时长（App.vue SILENCE） */
        const val SILENCE_MS = 1000L
    }

    @Volatile
    var state: ChatState = ChatState.IDLE
        private set

    private var silencePending = false

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
                if (level > THRESHOLD_SPEAKING) {
                    transition(ChatState.USER_SPEAKING)
                }
            }

            ChatState.USER_SPEAKING -> {
                callbacks.sendAudioData(frame)
                if (level < THRESHOLD_SPEAKING) {
                    if (!silencePending) {
                        silencePending = true
                        scheduler.schedule(SILENCE_MS) {
                            silencePending = false
                            transition(ChatState.AI_SPEAKING)
                        }
                    }
                } else {
                    cancelSilence()
                    callbacks.onUserWaveLevel(level)
                }
            }

            ChatState.AI_SPEAKING -> {
                if (level > THRESHOLD_INTERRUPT) {
                    callbacks.sendTextData(AbortMessage(sessionId = callbacks.getSessionId()))
                    transition(ChatState.USER_SPEAKING)
                }
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
            ChatState.USER_SPEAKING -> {
                callbacks.sendTextData(ListenMessage.start())
                callbacks.onEvent(ChatEvent.USER_START_SPEAKING)
            }
            ChatState.AI_SPEAKING -> callbacks.onEvent(ChatEvent.AI_START_SPEAKING)
        }
    }

    private fun exitUserSpeaking() {
        cancelSilence()
        callbacks.sendTextData(ListenMessage.stop())
        callbacks.onEvent(ChatEvent.USER_STOP_SPEAKING)
    }

    private fun cancelSilence() {
        if (silencePending) {
            scheduler.cancel()
            silencePending = false
        }
    }
}
