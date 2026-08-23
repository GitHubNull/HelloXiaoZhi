package org.oxff.helloxiaozhi.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import kotlin.random.Random
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.chat.ChatState
import org.oxff.helloxiaozhi.controller.XiaoZhiController

/**
 * 语音通话页（对应 Web 端 VoiceCall.vue）：深色全屏 + AI 头像
 * 缩放动画（AI 说话时）+ 3 层涟漪扩散 + 10 条用户声浪波形
 * （高度由音频电平驱动）+ 挂断按钮。
 */
class VoiceCallActivity : AppCompatActivity() {

    private lateinit var controller: XiaoZhiController
    private lateinit var avatarContainer: FrameLayout
    private val waveViews = mutableListOf<View>()

    private val rippleAnimators = mutableListOf<Animator>()
    private var avatarAnimator: Animator? = null
    private var waveAnimator: ValueAnimator? = null

    /** 当前用户声浪电平（controller 录音线程回调写入） */
    @Volatile
    private var currentLevel = 0f

    private val maxWaveHeightPx by lazy {
        (96 * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_call)
        controller = (application as XiaoZhiApp).controller

        avatarContainer = findViewById(R.id.avatar_container)
        for (i in 0 until WAVE_COUNT) {
            val id = resources.getIdentifier("wave_$i", "id", packageName)
            waveViews.add(findViewById(id))
        }

        findViewById<View>(R.id.btn_hangup).setOnClickListener { hangUp() }
        findViewById<View>(R.id.btn_back).setOnClickListener { hangUp() }

        startRippleAnimation()
        startWaveAnimation()

        controller.onUserWaveLevel = { level -> currentLevel = level }
        controller.onChatStateChanged = { state -> updateAvatarAnimation(state) }
        updateAvatarAnimation(controller.chatState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        hangUp()
    }

    override fun onDestroy() {
        controller.onUserWaveLevel = null
        controller.onChatStateChanged = null
        rippleAnimators.forEach { it.cancel() }
        rippleAnimators.clear()
        avatarAnimator?.cancel()
        waveAnimator?.cancel()
        super.onDestroy()
    }

    /** 挂断：停止采集并退出（对应 App.vue closeVoiceCallPanel） */
    private fun hangUp() {
        controller.stopVoiceCall()
        finish()
    }

    /** 3 层涟漪扩散动画（对应 Web 端 ripple-1/2/3，相位依次错开） */
    private fun startRippleAnimation() {
        val rippleIds = intArrayOf(R.id.ripple_1, R.id.ripple_2, R.id.ripple_3)
        rippleIds.forEachIndexed { index, id ->
            val view = findViewById<View>(id)
            val set = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.9f),
                    ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.9f),
                    ObjectAnimator.ofFloat(view, "alpha", 0.8f, 0f),
                )
                duration = RIPPLE_DURATION_MS
                startDelay = index * RIPPLE_STAGGER_MS
            }
            set.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isFinishing) animation.start()
                }
            })
            rippleAnimators.add(set)
            set.start()
        }
    }

    /** AI 说话时头像呼吸缩放（对应 Web 端 scale-avatar） */
    private fun updateAvatarAnimation(state: ChatState) {
        if (state == ChatState.AI_SPEAKING) {
            if (avatarAnimator?.isRunning == true) return
            avatarAnimator?.cancel()
            avatarAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(avatarContainer, "scaleX", 1f, 1.05f).apply {
                        duration = AVATAR_PULSE_MS
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                    },
                    ObjectAnimator.ofFloat(avatarContainer, "scaleY", 1f, 1.05f).apply {
                        duration = AVATAR_PULSE_MS
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                    },
                )
            }
            avatarAnimator?.start()
        } else {
            avatarAnimator?.cancel()
            avatarAnimator = null
            avatarContainer.scaleX = 1f
            avatarContainer.scaleY = 1f
        }
    }

    /** 用户声浪波形动画（对应 Web 端 voice-wave：电平驱动随机起伏） */
    private fun startWaveAnimation() {
        waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = WAVE_FRAME_MS
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                for (view in waveViews) {
                    val scale = (0.15f + 0.85f * currentLevel * Random.nextFloat())
                        .coerceIn(MIN_WAVE_SCALE, 1f)
                    val height = (maxWaveHeightPx * scale).toInt()
                    val lp = view.layoutParams
                    lp.height = maxOf(height, MIN_WAVE_HEIGHT_PX)
                    view.layoutParams = lp
                }
            }
            start()
        }
    }

    private companion object {
        const val WAVE_COUNT = 10
        const val RIPPLE_DURATION_MS = 2400L
        const val RIPPLE_STAGGER_MS = 800L
        const val AVATAR_PULSE_MS = 500L
        const val WAVE_FRAME_MS = 120L
        const val MIN_WAVE_SCALE = 0.08f
        const val MIN_WAVE_HEIGHT_PX = 8
    }
}
