package org.oxff.helloxiaozhi.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.oxff.helloxiaozhi.ui.view.StarfieldCallView
import org.oxff.helloxiaozhi.ui.view.ToastHost
import org.oxff.helloxiaozhi.util.TimeFormat

/**
 * 语音通话页（对应设计稿 call.html）：
 * 二进制星河动画（双球由真实 ChatState 驱动）+ 通话计时 + 历史记录 +
 * 三按钮控制栏（机器人增益 / 挂断 / 你的增益）。
 */
class VoiceCallActivity : AppCompatActivity() {

    private lateinit var controller: XiaoZhiController
    private lateinit var starfield: StarfieldCallView
    private lateinit var callState: TextView
    private lateinit var callTimer: TextView
    private lateinit var historyList: RecyclerView
    private lateinit var toastHost: ToastHost

    private val historyAdapter = MessageAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var callSeconds = 0
    private var callStarted = false
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!callStarted) return
            callSeconds++
            callTimer.text = TimeFormat.duration(callSeconds)
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_call)
        controller = (application as XiaoZhiApp).controller

        bindViews()
        bindController()
        startCall()
    }

    override fun onResume() {
        super.onResume()
        starfield.start()
    }

    override fun onPause() {
        super.onPause()
        // 锁屏 / 退后台即停动画，避免烧 CPU
        starfield.stop()
    }

    override fun onDestroy() {
        unbindController()
        mainHandler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        hangUp()
    }

    // ---------------- 视图绑定 ----------------

    private fun bindViews() {
        starfield = findViewById(R.id.starfield)
        callState = findViewById(R.id.call_state)
        callTimer = findViewById(R.id.call_timer)
        historyList = findViewById(R.id.call_history)
        toastHost = ToastHost(this).also {
            (findViewById<android.view.ViewGroup>(android.R.id.content)).addView(it)
        }

        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = historyAdapter

        findViewById<View>(R.id.btn_hangup).setOnClickListener { hangUp() }
        setupGainPopover(
            btnId = R.id.btn_ai_gain,
            popId = R.id.ai_gain_pop,
            titleRes = R.string.call_gain_ai,
            onGain = { percent -> controller.setPlaybackGain(percent / 100f * 2f) },
        )
        setupGainPopover(
            btnId = R.id.btn_user_gain,
            popId = R.id.user_gain_pop,
            titleRes = R.string.call_gain_user,
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
        onGain: (Int) -> Unit,
    ) {
        val btn = findViewById<View>(btnId)
        val pop = findViewById<View>(popId)
        val slider = pop.findViewById<SeekBar>(R.id.gain_slider)
        val value = pop.findViewById<TextView>(R.id.gain_value)
        pop.findViewById<TextView>(R.id.gain_pop_title).setText(titleRes)
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
        controller.onError = { message ->
            toastHost.show(message, ToastHost.Kind.ERROR)
        }
        updateCallState(controller.chatState)
    }

    private fun unbindController() {
        controller.onChatStateChanged = null
        controller.onChatMessage = null
        controller.onError = null
    }

    // ---------------- 通话状态与动画 ----------------

    private fun startCall() {
        // 对应设计稿 call.js：1.2s 后接通
        mainHandler.postDelayed({
            callStarted = true
            toastHost.show(getString(R.string.call_connected), ToastHost.Kind.SUCCESS, 1500)
            updateCallState(controller.chatState)
        }, 1200)
        mainHandler.postDelayed(timerRunnable, 1000)
    }

    private fun updateCallState(state: ChatState) {
        when (state) {
            ChatState.USER_SPEAKING -> {
                callState.text = getString(R.string.call_state_user)
                callState.setTextColor(getColor(R.color.xz_primary))
                starfield.setUserSpeaking(true)
                starfield.setAiSpeaking(false)
            }
            ChatState.AI_SPEAKING -> {
                callState.text = getString(R.string.call_state_ai, getString(R.string.call_label_ai))
                callState.setTextColor(getColor(R.color.xz_accent_ai))
                starfield.setUserSpeaking(false)
                starfield.setAiSpeaking(true)
            }
            ChatState.IDLE -> {
                callState.text = getString(R.string.call_state_idle)
                callState.setTextColor(getColor(R.color.xz_text_secondary))
                starfield.setUserSpeaking(false)
                starfield.setAiSpeaking(false)
            }
        }
    }

    /** 挂断：停止采集并退出（对应 App.vue closeVoiceCallPanel） */
    private fun hangUp() {
        controller.stopVoiceCall()
        toastHost.show(getString(R.string.call_finished), ToastHost.Kind.NORMAL, 1200)
        mainHandler.postDelayed({ finish() }, 900)
    }
}
