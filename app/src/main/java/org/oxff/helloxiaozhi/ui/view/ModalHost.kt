package org.oxff.helloxiaozhi.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R

/**
 * 模态框宿主，对应设计稿 .modal-mask + .modal-card：
 * 遮罩淡入 + 卡片 scale(0.9)→1 弹入。
 *
 * 模态框放在 Activity 的 FrameLayout **内部**（不用 Dialog），才能继承设计
 * 并叠在对话详情滑入层之上（设计稿 z-index: 模态 100 > 详情 30 > Tab 栏 20）。
 */
class ModalHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 是否有模态框正在展示（供 Activity 协调返回键） */
    val isShowing: Boolean get() = childCount > 0

    /** 点击遮罩是否关闭（默认关闭） */
    var dismissOnScrimClick = true

    /** 模态框关闭回调 */
    var onDismissed: (() -> Unit)? = null

    init {
        visibility = GONE
        setBackgroundColor(ContextCompat.getColor(context, R.color.xz_scrim))
        // 遮罩消费触摸，下层不响应
        isClickable = true
        isFocusable = true
        setOnClickListener { if (dismissOnScrimClick) dismiss() }
    }

    /**
     * 展示一个模态卡片。卡片内容点击不冒泡到遮罩（不触发关闭）。
     */
    fun show(card: View) {
        removeAllViews()
        // 卡片点击不冒泡到遮罩
        card.isClickable = true
        addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.CENTER
                marginStart = resources.getDimensionPixelSize(R.dimen.xz_gutter) * 2
                marginEnd = marginStart
            },
        )
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(ANIM_MS).start()
        // 卡片弹入：scale(0.9) + translateY(16px) → 1
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.translationY = dp(16)
        card.animate()
            .scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(CARD_ANIM_MS)
            .setInterpolator(INTERPOLATOR)
            .start()
    }

    fun dismiss() {
        if (!isShowing) return
        animate().alpha(0f).setDuration(ANIM_MS).withEndAction {
            visibility = GONE
            removeAllViews()
            onDismissed?.invoke()
        }.start()
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    private companion object {
        const val ANIM_MS = 300L
        const val CARD_ANIM_MS = 320L
        val INTERPOLATOR = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    }
}
