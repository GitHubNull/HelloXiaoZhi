package org.oxff.helloxiaozhi.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

/**
 * 对话详情滑入容器，对应设计稿 .chat-detail-view：
 * 默认 translateX(100%) 隐藏在右侧，open() 滑入、close() 滑出。
 *
 * 打开时拦截触摸，避免下层的会话列表收到事件。
 */
class SlideInContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var isOpen = false
        private set

    /** 打开状态变化回调（供 Activity 协调返回键） */
    var onOpenChanged: ((Boolean) -> Unit)? = null

    init {
        // 初始隐藏在右侧；onLayout 后再定位，此时 width 还是 0
        visibility = GONE
        // 打开时拦截触摸，下层列表不响应
        isClickable = true
        isFocusable = true
    }

    fun open(animate: Boolean = true) {
        if (isOpen) return
        isOpen = true
        onOpenChanged?.invoke(true)
        visibility = VISIBLE
        if (animate && width > 0) {
            translationX = width.toFloat()
            animate().translationX(0f)
                .setDuration(DURATION_MS)
                .setInterpolator(INTERPOLATOR)
                .start()
        } else {
            translationX = 0f
        }
    }

    fun close(animate: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        onOpenChanged?.invoke(false)
        if (animate && width > 0) {
            animate().translationX(width.toFloat())
                .setDuration(DURATION_MS)
                .setInterpolator(INTERPOLATOR)
                .withEndAction { visibility = GONE }
                .start()
        } else {
            translationX = width.toFloat()
            visibility = GONE
        }
    }

    private companion object {
        const val DURATION_MS = 300L

        /** 对应设计稿 cubic-bezier(0.22, 1, 0.36, 1) */
        val INTERPOLATOR = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    }
}
