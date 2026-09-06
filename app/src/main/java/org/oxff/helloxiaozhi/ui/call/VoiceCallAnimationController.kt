package org.oxff.helloxiaozhi.ui.call

import android.os.Handler
import android.os.Looper
import android.view.View
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.chat.ChatState
import org.oxff.helloxiaozhi.ui.view.RippleCallView
import org.oxff.helloxiaozhi.util.TimeFormat

/**
 * 语音通话动画控制器：负责通话动画控制。
 *
 * 职责：
 *  - 管理通话状态动画
 *  - 管理通话计时器
 *  - 处理文字/动画视图切换
 *
 * 从 VoiceCallActivity 拆分而来，专注于动画控制职责。
 */
class VoiceCallAnimationController(
    private val rippleView: RippleCallView,
    private val callState: android.widget.TextView,
    private val callTimer: android.widget.TextView,
    private val animPanel: View,
    private val historyList: View,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var callSeconds = 0
    private var callStarted = false
    private var isAnimMode = true

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!callStarted) return
            callSeconds++
            callTimer.text = TimeFormat.duration(callSeconds)
            mainHandler.postDelayed(this, 1000)
        }
    }

    /**
     * 开始通话
     */
    fun startCall(onCallStarted: () -> Unit) {
        mainHandler.postDelayed({
            callStarted = true
            onCallStarted()
            mainHandler.postDelayed(timerRunnable, 1000)
        }, 1200)
    }

    /**
     * 更新通话状态
     */
    fun updateCallState(state: ChatState) {
        when (state) {
            ChatState.USER_SPEAKING -> {
                callState.text = callState.context.getString(R.string.call_state_user)
                callState.setTextColor(callState.context.getColor(R.color.xz_primary))
                rippleView.setUserSpeaking(true)
                rippleView.setAiSpeaking(false)
            }
            ChatState.AI_SPEAKING -> {
                callState.text = callState.context.getString(R.string.call_state_ai, callState.context.getString(R.string.call_label_ai))
                callState.setTextColor(callState.context.getColor(R.color.xz_accent_ai))
                rippleView.setUserSpeaking(false)
                rippleView.setAiSpeaking(true)
            }
            ChatState.IDLE -> {
                callState.text = callState.context.getString(R.string.call_state_idle)
                callState.setTextColor(callState.context.getColor(R.color.xz_text_secondary))
                rippleView.setUserSpeaking(false)
                rippleView.setAiSpeaking(false)
            }
        }
    }

    /**
     * 切换文字/动画视图
     */
    fun toggleView() {
        isAnimMode = !isAnimMode
        animPanel.visibility = if (isAnimMode) View.VISIBLE else View.GONE
        historyList.visibility = if (isAnimMode) View.GONE else View.VISIBLE
    }

    /**
     * 停止计时器
     */
    fun stopTimer() {
        mainHandler.removeCallbacks(timerRunnable)
    }

    /**
     * 是否已开始通话
     */
    fun isCallStarted(): Boolean = callStarted
}
