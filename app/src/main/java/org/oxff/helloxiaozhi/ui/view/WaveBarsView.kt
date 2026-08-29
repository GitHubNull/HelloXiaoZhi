package org.oxff.helloxiaozhi.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R
import kotlin.random.Random

/**
 * 用户声浪波形条，对应设计稿通话页的 voice-wave。
 *
 * 取代旧实现的 10 个硬编码 View + 每 120ms 改 layoutParams 的循环
 * （那会在每帧触发一次完整 layout pass）。这里从电平环形缓冲自绘圆角条，
 * onDraw 零分配。
 */
class WaveBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 当前电平（0..1），由录音线程写入 */
    @Volatile
    var level = 0f

    private val barCount = 10
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xz_primary)
    }
    private val barRect = RectF()
    private val barHeights = FloatArray(barCount)

    private var running = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step()
            invalidate()
            android.view.Choreographer.getInstance().postFrameCallbackDelayed(this, FRAME_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun step() {
        // 电平驱动 + 随机起伏，对应设计稿 LevelGenerator 的正弦基底 + 噪声
        for (i in 0 until barCount) {
            val wave = (0.15f + 0.85f * level * Random.nextFloat()).coerceIn(MIN_SCALE, 1f)
            barHeights[i] = wave
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val barWidth = w / (barCount * 2 - 1)
        val gap = barWidth
        for (i in 0 until barCount) {
            val bh = maxOf(h * barHeights[i], MIN_HEIGHT_PX)
            val left = i * (barWidth + gap)
            barRect.set(left, (h - bh) / 2f, left + barWidth, (h + bh) / 2f)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
        }
    }

    private companion object {
        const val FRAME_MS = 120L
        const val MIN_SCALE = 0.08f
        const val MIN_HEIGHT_PX = 8f
    }
}
