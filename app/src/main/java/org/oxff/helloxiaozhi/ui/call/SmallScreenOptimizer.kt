package org.oxff.helloxiaozhi.ui.call

import android.app.Activity
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import org.oxff.helloxiaozhi.R

/**
 * 小屏优化器：负责小屏专属优化逻辑。
 *
 * 职责：
 *  - 管理小屏模式的进入策略
 *  - 处理纯动画界面与完整界面的切换
 *  - 管理倒计时弹窗
 *
 * 从 VoiceCallActivity 拆分而来，专注于小屏优化职责。
 */
class SmallScreenOptimizer(
    private val activity: Activity,
    private val statusBlock: View,
    private val bottomBar: View,
    private val middleArea: View,
    private val poolFrame: View,
    private val countdownOverlay: View,
    private val countdownText: android.widget.TextView,
    private val rippleView: org.oxff.helloxiaozhi.ui.view.RippleCallView,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var collapsed = false
    private var tapCount = 0
    private var lastTapMs = 0L
    private var countdownTimer: CountDownTimer? = null

    private val expandRunnable = Runnable {
        tapCount = 0
        expand()
    }

    /**
     * 是否为小屏设备
     */
    val isSmallScreen: Boolean by lazy {
        activity.resources.configuration.smallestScreenWidthDp < SMALL_SCREEN_SW_DP
    }

    /**
     * 设置小屏模式
     */
    fun setupSmallScreenMode() {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
        val entries = prefs.getInt(KEY_FULL_UI_ENTRIES, 0) + 1
        prefs.edit().putInt(KEY_FULL_UI_ENTRIES, entries).apply()
        if (entries > MAX_FULL_UI_ENTRIES) {
            collapse()
        } else {
            showCollapseCountdown()
        }
    }

    /**
     * 显示收起倒计时
     */
    private fun showCollapseCountdown() {
        countdownOverlay.visibility = View.VISIBLE
        countdownTimer = object : CountDownTimer(COLLAPSE_DELAY_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = ((millisUntilFinished + 999) / 1000).toInt()
                countdownText.text = activity.getString(R.string.call_countdown_title, seconds)
            }

            override fun onFinish() {
                countdownOverlay.visibility = View.GONE
                collapse()
            }
        }.start()
    }

    /**
     * 收起为纯动画界面
     */
    fun collapse() {
        if (collapsed) return
        collapsed = true
        countdownTimer?.cancel()
        countdownOverlay.visibility = View.GONE
        statusBlock.visibility = View.GONE
        bottomBar.visibility = View.GONE
        middleArea.setPadding(0, 0, 0, 0)
        poolFrame.setPadding(0, 0, 0, 0)
        poolFrame.background = null
        rippleView.setEdgeInset(2f, 2f)
    }

    /**
     * 退回完整功能界面
     */
    fun expand() {
        if (!collapsed) return
        collapsed = false
        statusBlock.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        middleArea.setPadding(sidePadPx, 0, sidePadPx, 0)
        poolFrame.setPadding(poolPadPx, poolPadPx, poolPadPx, poolPadPx)
        poolFrame.background = poolBg
        rippleView.resetEdgeInset()
    }

    /**
     * 处理纯动画界面下的点击
     */
    fun onCollapsedTap(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTapMs > TAP_WINDOW_MS) tapCount = 0
        lastTapMs = now
        tapCount++
        return when {
            tapCount >= 3 -> {
                mainHandler.removeCallbacks(expandRunnable)
                tapCount = 0
                true // 挂断
            }
            tapCount == 2 -> {
                mainHandler.postDelayed(expandRunnable, TRIPLE_TAP_GUARD_MS)
                false
            }
            else -> false
        }
    }

    /**
     * 是否已收起
     */
    fun isCollapsed(): Boolean = collapsed

    /**
     * 清理资源
     */
    fun cleanup() {
        countdownTimer?.cancel()
        mainHandler.removeCallbacks(expandRunnable)
    }

    var sidePadPx = 0
    var poolPadPx = 0
    var poolBg: android.graphics.drawable.Drawable? = null

    private companion object {
        const val SMALL_SCREEN_SW_DP = 360
        const val MAX_FULL_UI_ENTRIES = 3
        const val COLLAPSE_DELAY_MS = 8000L
        const val TAP_WINDOW_MS = 2000L
        const val TRIPLE_TAP_GUARD_MS = 500L
        const val PREFS_NAME = "call_ui_prefs"
        const val KEY_FULL_UI_ENTRIES = "full_ui_entries"
    }
}
