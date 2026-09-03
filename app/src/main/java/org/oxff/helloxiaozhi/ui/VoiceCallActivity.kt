package org.oxff.helloxiaozhi.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.chat.ChatState
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.data.StoredMessage
import org.oxff.helloxiaozhi.ui.view.RippleCallView
import org.oxff.helloxiaozhi.ui.view.ToastHost
import org.oxff.helloxiaozhi.util.TimeFormat

/**
 * 语音通话页（对应设计稿 call.html）：
 * 水波涟漪动画（单球由真实 ChatState 驱动）+ 通话计时 + 历史记录 +
 * 三按钮控制栏（机器人增益 / 挂断 / 你的增益）。
 * 文字/动画视图切换由双击动画区域触发（小屏空间优化移除了顶部切换栏）。
 */
class VoiceCallActivity : AppCompatActivity() {

    private lateinit var controller: XiaoZhiController
    private lateinit var rippleView: RippleCallView
    private lateinit var callState: TextView
    private lateinit var callTimer: TextView
    private lateinit var historyList: RecyclerView
    private lateinit var toastHost: ToastHost
    private lateinit var animPanel: View
    private lateinit var statusBlock: View
    private lateinit var bottomBar: View
    private lateinit var middleArea: View
    private lateinit var poolFrame: View
    private lateinit var countdownOverlay: View
    private lateinit var countdownText: TextView

    private val historyAdapter = MessageAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var callSeconds = 0
    private var callStarted = false
    private var isAnimMode = true // true=动画模式，false=文字模式

    // ---------------- 小屏专属优化状态 ----------------
    /** 小屏判定：本机 308x240@120dpi = 410x320dp，sw=320dp；普通手机 sw>=360dp 不受影响 */
    private val isSmallScreen by lazy {
        resources.configuration.smallestScreenWidthDp < SMALL_SCREEN_SW_DP
    }
    private var collapsed = false // 纯动画界面模式
    private var tapCount = 0
    private var lastTapMs = 0L
    private var countdownTimer: CountDownTimer? = null
    private var sidePadPx = 0
    private var poolPadPx = 0
    private var poolBg: android.graphics.drawable.Drawable? = null
    // 完整界面下双击动画区域切换文字/动画（替代已移除的顶部切换栏）
    private var expandedTapCount = 0
    private var lastExpandedTapMs = 0L
    private val middleRect = android.graphics.Rect()
    private val expandRunnable = Runnable {
        tapCount = 0
        expand()
    }
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!callStarted) return
            callSeconds++
            callTimer.text = TimeFormat.duration(callSeconds)
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 方向由 manifest screenOrientation="behind" 继承 MainActivity
        // （MainActivity 已通过 OrientationPolicy 在小屏原生横屏真机上锁定 landscape），
        // 避免在 onCreate 中动态 setRequestedOrientation 触发「窗口创建→配置变更」两阶段旋转动画。
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_call)
        controller = (application as XiaoZhiApp).controller

        bindViews()
        bindController()
        startCall()
        if (isSmallScreen) setupSmallScreenMode()
    }

    override fun onResume() {
        super.onResume()
        rippleView.start()
    }

    override fun onPause() {
        super.onPause()
        // 锁屏 / 退后台即停动画，避免烧 CPU
        rippleView.stop()
    }

    override fun onDestroy() {
        unbindController()
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(expandRunnable)
        countdownTimer?.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        hangUp()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 纯动画界面下整个窗口即动画区域：在窗口最上游捕获点击，
        // 不依赖子 View 命中测试，连点 2 次恢复完整界面、3 次挂断回主页。
        // 完整界面下双击动画/文字区域切换文字/动画；只计数不拦截事件，
        // 增益弹层收起等子 View 点击不受影响。
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (collapsed) onCollapsedTap() else onExpandedTap(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 完整界面双击切换文字/动画：仅响应 middle_area 内的点击，避免误触底部控制栏按钮 */
    private fun onExpandedTap(ev: MotionEvent) {
        middleArea.getGlobalVisibleRect(middleRect)
        if (!middleRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
            expandedTapCount = 0
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastExpandedTapMs > TAP_WINDOW_MS) expandedTapCount = 0
        lastExpandedTapMs = now
        expandedTapCount++
        if (expandedTapCount >= 2) {
            expandedTapCount = 0
            toggleView()
        }
    }

    // ---------------- 视图绑定 ----------------

    private fun bindViews() {
        rippleView = findViewById(R.id.ripple_view)
        callState = findViewById(R.id.call_state)
        callTimer = findViewById(R.id.call_timer)
        historyList = findViewById(R.id.call_history)
        animPanel = findViewById(R.id.anim_panel)
        statusBlock = findViewById(R.id.status_block)
        bottomBar = findViewById(R.id.bottom_bar)
        middleArea = findViewById(R.id.middle_area)
        poolFrame = findViewById(R.id.pool_frame)
        countdownOverlay = findViewById(R.id.countdown_overlay)
        countdownText = findViewById(R.id.countdown_text)
        sidePadPx = resources.getDimensionPixelSize(R.dimen.call_side_pad)
        poolPadPx = resources.getDimensionPixelSize(R.dimen.call_pool_pad)
        poolBg = poolFrame.background
        toastHost = ToastHost(this).also {
            (findViewById<android.view.ViewGroup>(android.R.id.content)).addView(it)
        }

        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = historyAdapter

        findViewById<View>(R.id.btn_hangup).setOnClickListener { hangUp() }

        // 初始 progress 与 recorder 实际默认值对齐（布局默认 70 会造成
        // 「显示与实际不符」的迷惑）：50% = 1.0x / 0dB
        setupGainPopover(
            btnId = R.id.btn_ai_gain,
            popId = R.id.ai_gain_pop,
            titleRes = R.string.call_gain_ai,
            initialProgress = 50,
            onGain = { percent -> controller.setPlaybackGain(percent / 100f * 2f) },
        )
        setupGainPopover(
            btnId = R.id.btn_user_gain,
            popId = R.id.user_gain_pop,
            titleRes = R.string.call_gain_user,
            initialProgress = 50,
            onGain = { percent ->
                // 0..100% 映射到 -12..+12 dB（相对基准），与 VAD 解耦
                controller.setMicGainDb((percent / 100f) * 24f - 12f)
            },
        )
        // 点击空白处收起弹出层
        findViewById<View>(android.R.id.content).setOnClickListener {
            findViewById<View>(R.id.ai_gain_pop).visibility = View.GONE
            findViewById<View>(R.id.user_gain_pop).visibility = View.GONE
        }
    }

    private fun setupGainPopover(
        btnId: Int,
        popId: Int,
        titleRes: Int,
        initialProgress: Int,
        onGain: (Int) -> Unit,
    ) {
        val btn = findViewById<View>(btnId)
        val pop = findViewById<View>(popId)
        val slider = pop.findViewById<SeekBar>(R.id.gain_slider)
        val value = pop.findViewById<TextView>(R.id.gain_value)
        pop.findViewById<TextView>(R.id.gain_pop_title).setText(titleRes)
        // 先设置初始进度再注册监听，避免初始化触发 onGain
        slider.progress = initialProgress
        value.text = getString(R.string.call_gain_value, slider.progress)

        btn.setOnClickListener {
            val other = if (popId == R.id.ai_gain_pop) R.id.user_gain_pop else R.id.ai_gain_pop
            findViewById<View>(other).visibility = View.GONE
            pop.visibility = if (pop.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                value.text = getString(R.string.call_gain_value, progress)
                if (fromUser) onGain(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------------- 视图切换 ----------------

    /** 文字/动画互斥切换：顶部切换栏移除后由双击动画区域触发 */
    private fun toggleView() {
        isAnimMode = !isAnimMode
        animPanel.visibility = if (isAnimMode) View.VISIBLE else View.GONE
        historyList.visibility = if (isAnimMode) View.GONE else View.VISIBLE
    }

    // ---------------- Controller 回调 ----------------

    private fun bindController() {
        controller.onChatStateChanged = { state -> updateCallState(state) }
        controller.onChatMessage = { _, message ->
            // 通话页只展示当前机器人的消息（通话期间 activeBotId 不变）
            historyAdapter.add(
                StoredMessage(
                    role = message.role,
                    content = message.content,
                    ts = System.currentTimeMillis(),
                ),
            )
            historyList.scrollToPosition(historyAdapter.itemCount - 1)
        }
        controller.onUserWaveLevel = { level ->
            // 用户说话电平驱动用户球体水波动画
            rippleView.setUserAudioIntensity(level)
        }
        controller.onAiWaveLevel = { level ->
            // AI 说话电平驱动 AI 球体水波动画
            rippleView.setAiAudioIntensity(level)
        }
        controller.onError = { message ->
            toastHost.show(message, ToastHost.Kind.ERROR)
        }
        updateCallState(controller.chatState)
    }

    private fun unbindController() {
        controller.onChatStateChanged = null
        controller.onChatMessage = null
        controller.onUserWaveLevel = null
        controller.onAiWaveLevel = null
        controller.onError = null
    }

    // ---------------- 通话状态与动画 ----------------

    private fun startCall() {
        // 对应设计稿 call.js：1.2s 后接通；计时器必须在 callStarted 置位后才启动，
        // 否则首次执行命中 if (!callStarted) return 后不再自我投递，计时链永久中断（显示恒为 0）
        mainHandler.postDelayed({
            callStarted = true
            toastHost.show(getString(R.string.call_connected), ToastHost.Kind.SUCCESS, 1500)
            updateCallState(controller.chatState)
            mainHandler.postDelayed(timerRunnable, 1000)
        }, 1200)
    }

    private fun updateCallState(state: ChatState) {
        when (state) {
            ChatState.USER_SPEAKING -> {
                callState.text = getString(R.string.call_state_user)
                callState.setTextColor(getColor(R.color.xz_primary))
                rippleView.setUserSpeaking(true)
                rippleView.setAiSpeaking(false)
            }
            ChatState.AI_SPEAKING -> {
                callState.text = getString(R.string.call_state_ai, getString(R.string.call_label_ai))
                callState.setTextColor(getColor(R.color.xz_accent_ai))
                rippleView.setUserSpeaking(false)
                rippleView.setAiSpeaking(true)
            }
            ChatState.IDLE -> {
                callState.text = getString(R.string.call_state_idle)
                callState.setTextColor(getColor(R.color.xz_text_secondary))
                rippleView.setUserSpeaking(false)
                rippleView.setAiSpeaking(false)
            }
        }
    }

    /** 挂断：停止采集并退出（对应 App.vue closeVoiceCallPanel） */
    private fun hangUp() {
        controller.stopVoiceCall()
        toastHost.show(getString(R.string.call_finished), ToastHost.Kind.NORMAL, 1200)
        mainHandler.postDelayed({ finish() }, 900)
    }

    // ---------------- 小屏专属优化 ----------------

    /**
     * 小屏进入策略：前 [MAX_FULL_UI_ENTRIES] 次停留完整界面 8 秒（带倒计时弹窗），
     * 之后自动收起为纯动画界面；超过次数后不再倒计时，直接进入纯动画界面。
     */
    private fun setupSmallScreenMode() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val entries = prefs.getInt(KEY_FULL_UI_ENTRIES, 0) + 1
        prefs.edit().putInt(KEY_FULL_UI_ENTRIES, entries).apply()
        android.util.Log.i(TAG, "onCreate small-screen entries=$entries")
        if (entries > MAX_FULL_UI_ENTRIES) {
            collapse()
        } else {
            showCollapseCountdown()
        }
    }

    private fun showCollapseCountdown() {
        countdownOverlay.visibility = View.VISIBLE
        countdownTimer = object : CountDownTimer(COLLAPSE_DELAY_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = ((millisUntilFinished + 999) / 1000).toInt()
                countdownText.text = getString(R.string.call_countdown_title, seconds)
            }

            override fun onFinish() {
                countdownOverlay.visibility = View.GONE
                collapse()
            }
        }.start()
    }

    /** 收起为纯动画界面：隐藏顶/底栏与状态块，涟漪铺满全屏 */
    private fun collapse() {
        if (collapsed) return
        collapsed = true
        countdownTimer?.cancel()
        countdownOverlay.visibility = View.GONE
        if (!isAnimMode) toggleView()
        statusBlock.visibility = View.GONE
        bottomBar.visibility = View.GONE
        middleArea.setPadding(0, 0, 0, 0)
        poolFrame.setPadding(0, 0, 0, 0)
        poolFrame.background = null
        rippleView.setEdgeInset(2f, 2f)
    }

    /** 退回完整功能界面（连点动画中心 2 次） */
    private fun expand() {
        if (!collapsed) return
        android.util.Log.i(TAG, "expand")
        collapsed = false
        statusBlock.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        middleArea.setPadding(sidePadPx, 0, sidePadPx, 0)
        poolFrame.setPadding(poolPadPx, poolPadPx, poolPadPx, poolPadPx)
        poolFrame.background = poolBg
        rippleView.resetEdgeInset()
    }

    /** 纯动画界面下的点击：2 次→恢复完整界面；3 次→挂断并回到主页 */
    private fun onCollapsedTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapMs > TAP_WINDOW_MS) tapCount = 0
        lastTapMs = now
        tapCount++
        android.util.Log.i(TAG, "collapsedTap count=$tapCount")
        when {
            tapCount >= 3 -> {
                mainHandler.removeCallbacks(expandRunnable)
                tapCount = 0
                hangUp()
            }
            tapCount == 2 ->
                // 留一个窗口等第 3 次点击；未等到才恢复完整界面
                mainHandler.postDelayed(expandRunnable, TRIPLE_TAP_GUARD_MS)
        }
    }

    private companion object {
        const val SMALL_SCREEN_SW_DP = 360
        const val MAX_FULL_UI_ENTRIES = 3
        const val COLLAPSE_DELAY_MS = 8000L
        // 连点窗口放宽到 2s：小屏真机注入/操作间隔偏大，过严会导致连点永远不成立
        const val TAP_WINDOW_MS = 2000L
        const val TRIPLE_TAP_GUARD_MS = 500L
        const val PREFS_NAME = "call_ui_prefs"
        const val KEY_FULL_UI_ENTRIES = "full_ui_entries"
        const val TAG = "VoiceCall"
    }
}

