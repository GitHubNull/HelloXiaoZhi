package org.oxff.helloxiaozhi.ui.call

import android.app.Activity
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.ui.MessageAdapter
import org.oxff.helloxiaozhi.ui.VoiceCallActivity
import org.oxff.helloxiaozhi.ui.view.RippleCallView
import org.oxff.helloxiaozhi.ui.view.ToastHost

/**
 * 语音通话页视图绑定器：负责视图绑定与初始化。
 *
 * 职责：
 *  - 绑定所有视图引用
 *  - 初始化 RecyclerView
 *  - 设置增益控制弹层
 *  - 设置点击监听器
 *
 * 从 VoiceCallActivity 拆分而来，专注于视图绑定职责。
 */
class VoiceCallViewBinder(
    private val activity: Activity,
    private val controller: XiaoZhiController,
) {
    lateinit var rippleView: RippleCallView
    lateinit var callState: TextView
    lateinit var callTimer: TextView
    lateinit var historyList: RecyclerView
    lateinit var toastHost: ToastHost
    lateinit var animPanel: View
    lateinit var statusBlock: View
    lateinit var bottomBar: View
    lateinit var middleArea: View
    lateinit var poolFrame: View
    lateinit var countdownOverlay: View
    lateinit var countdownText: TextView

    val historyAdapter = MessageAdapter()

    var sidePadPx = 0
    var poolPadPx = 0
    var poolBg: android.graphics.drawable.Drawable? = null

    /**
     * 绑定所有视图
     */
    fun bindViews() {
        rippleView = activity.findViewById(R.id.ripple_view)
        callState = activity.findViewById(R.id.call_state)
        callTimer = activity.findViewById(R.id.call_timer)
        historyList = activity.findViewById(R.id.call_history)
        animPanel = activity.findViewById(R.id.anim_panel)
        statusBlock = activity.findViewById(R.id.status_block)
        bottomBar = activity.findViewById(R.id.bottom_bar)
        middleArea = activity.findViewById(R.id.middle_area)
        poolFrame = activity.findViewById(R.id.pool_frame)
        countdownOverlay = activity.findViewById(R.id.countdown_overlay)
        countdownText = activity.findViewById(R.id.countdown_text)
        sidePadPx = activity.resources.getDimensionPixelSize(R.dimen.call_side_pad)
        poolPadPx = activity.resources.getDimensionPixelSize(R.dimen.call_pool_pad)
        poolBg = poolFrame.background
        toastHost = ToastHost(activity).also {
            (activity.findViewById<android.view.ViewGroup>(android.R.id.content)).addView(it)
        }

        historyList.layoutManager = LinearLayoutManager(activity)
        historyList.adapter = historyAdapter

        activity.findViewById<View>(R.id.btn_hangup).setOnClickListener { 
            (activity as? VoiceCallActivity)?.hangUp() 
        }

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
                controller.setMicGainDb((percent / 100f) * 24f - 12f)
            },
        )

        // 点击空白处收起弹出层
        activity.findViewById<View>(android.R.id.content).setOnClickListener {
            activity.findViewById<View>(R.id.ai_gain_pop).visibility = View.GONE
            activity.findViewById<View>(R.id.user_gain_pop).visibility = View.GONE
        }
    }

    /**
     * 设置增益控制弹层
     */
    private fun setupGainPopover(
        btnId: Int,
        popId: Int,
        titleRes: Int,
        initialProgress: Int,
        onGain: (Int) -> Unit,
    ) {
        val btn = activity.findViewById<View>(btnId)
        val pop = activity.findViewById<View>(popId)
        val slider = pop.findViewById<SeekBar>(R.id.gain_slider)
        val value = pop.findViewById<TextView>(R.id.gain_value)
        pop.findViewById<TextView>(R.id.gain_pop_title).setText(titleRes)
        slider.progress = initialProgress
        value.text = activity.getString(R.string.call_gain_value, slider.progress)

        btn.setOnClickListener {
            val other = if (popId == R.id.ai_gain_pop) R.id.user_gain_pop else R.id.ai_gain_pop
            activity.findViewById<View>(other).visibility = View.GONE
            pop.visibility = if (pop.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                value.text = activity.getString(R.string.call_gain_value, progress)
                if (fromUser) onGain(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
}
