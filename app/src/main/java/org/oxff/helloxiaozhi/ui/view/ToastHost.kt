package org.oxff.helloxiaozhi.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R

/**
 * 顶部胶囊提示，对应设计稿 .toast：
 * 从内容区顶部滑入，自动消失；success / error 态改变描边与文字色。
 *
 * 取代 Toast.makeText：系统 Toast 的样式与深色科技风设计不一致，
 * 且无法承载设计稿的滑入动画与语义色。
 */
class ToastHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class Kind { NORMAL, SUCCESS, ERROR }

    private val toastView: TextView = TextView(context).apply {
        visibility = GONE
        setTextColor(ContextCompat.getColor(context, R.color.xz_text_primary))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(20).toInt(), dp(10).toInt(), dp(20).toInt(), dp(10).toInt())
        setBackgroundResource(R.drawable.bg_toast)
        maxWidth = (resources.displayMetrics.widthPixels * 0.88f).toInt()
    }

    private var hideRunnable: Runnable? = null

    init {
        addView(
            toastView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(TOAST_TOP_DP).toInt()
            },
        )
        // 不拦截下层触摸
        isClickable = false
        isFocusable = false
    }

    fun show(message: String, kind: Kind = Kind.NORMAL, durationMs: Long = DURATION_MS) {
        hideRunnable?.let { removeCallbacks(it) }
        toastView.text = message
        val (borderColor, textColor) = when (kind) {
            Kind.SUCCESS -> R.color.xz_success to R.color.xz_success
            Kind.ERROR -> R.color.xz_error to R.color.xz_error
            Kind.NORMAL -> R.color.xz_border_subtle to R.color.xz_text_primary
        }
        toastView.setTextColor(ContextCompat.getColor(context, textColor))
        (toastView.background as? android.graphics.drawable.GradientDrawable)
            ?.setStroke(dp(1).toInt(), ContextCompat.getColor(context, borderColor))

        // 滑入：从上方 -80px 处落下（对应 .toast 的 translateY(-80px) → 0）
        toastView.visibility = VISIBLE
        toastView.translationY = -dp(80)
        toastView.alpha = 0f
        toastView.animate()
            .translationY(0f).alpha(1f)
            .setDuration(ANIM_MS)
            .setInterpolator(INTERPOLATOR)
            .start()

        val hide = Runnable { dismiss() }
        hideRunnable = hide
        postDelayed(hide, durationMs)
    }

    fun dismiss() {
        hideRunnable?.let { removeCallbacks(it) }
        hideRunnable = null
        toastView.animate()
            .translationY(-dp(80)).alpha(0f)
            .setDuration(ANIM_MS)
            .withEndAction { toastView.visibility = GONE }
            .start()
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    private companion object {
        const val TOAST_TOP_DP = 60
        const val ANIM_MS = 350L
        const val DURATION_MS = 2200L
        val INTERPOLATOR = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    }
}
