package org.oxff.helloxiaozhi.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R

/**
 * 开关控件，对应设计稿 .switch（index.css）：
 * 48×28 轨道 + 20 滑块，开启时轨道变青色、滑块右移。
 *
 * 选它而非 SwitchCompat：要匹配设计稿的几何尺寸与青色轨道，本来就得自定义
 * track/thumb drawable 加偏移 hack，自绘反而更直接、可控。
 */
class XzSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 开关状态变化回调（仅用户点击触发，代码 setChecked 不触发） */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    private val trackOff = ContextCompat.getColor(context, R.color.xz_switch_track_off)
    private val trackOn = ContextCompat.getColor(context, R.color.xz_switch_track_on)
    private val thumbOff = ContextCompat.getColor(context, R.color.xz_switch_thumb_off)
    private val thumbOn = ContextCompat.getColor(context, R.color.xz_primary)

    /** 0=关，1=开；动画驱动 */
    private var progress = 0f

    var isChecked = false
        private set

    private var animator: ValueAnimator? = null

    init {
        // 点击切换；按压反馈由 Pressable 叠加
        setOnClickListener { setChecked(!isChecked, animate = true, fromUser = true) }
    }

    fun setChecked(checked: Boolean, animate: Boolean = true, fromUser: Boolean = false) {
        if (isChecked == checked) return
        isChecked = checked
        animator?.cancel()
        val target = if (checked) 1f else 0f
        if (animate) {
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 250L
                interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            progress = target
            invalidate()
        }
        if (fromUser) onCheckedChange?.invoke(checked)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(SWITCH_WIDTH_DP).toInt(), widthMeasureSpec)
        val h = resolveSize(dp(SWITCH_HEIGHT_DP).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val trackRadius = h / 2f
        val thumbRadius = dp(THUMB_DP) / 2f
        val thumbMargin = (h - thumbRadius * 2) / 2f

        // 轨道颜色随进度插值
        trackPaint.color = lerpColor(trackOff, trackOn, progress)
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        // 滑块：左侧 margin → 右侧 margin
        val cx = thumbMargin + thumbRadius +
            (w - thumbMargin * 2 - thumbRadius * 2) * progress
        thumbPaint.color = lerpColor(thumbOff, thumbOn, progress)
        canvas.drawCircle(cx, h / 2f, thumbRadius, thumbPaint)
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val a = (android.graphics.Color.alpha(from) +
            (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t).toInt()
        val r = (android.graphics.Color.red(from) +
            (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t).toInt()
        val g = (android.graphics.Color.green(from) +
            (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t).toInt()
        val b = (android.graphics.Color.blue(from) +
            (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val SWITCH_WIDTH_DP = 48f
        const val SWITCH_HEIGHT_DP = 28f
        const val THUMB_DP = 20f
    }
}
