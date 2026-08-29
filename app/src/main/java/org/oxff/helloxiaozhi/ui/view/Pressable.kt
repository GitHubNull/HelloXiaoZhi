package org.oxff.helloxiaozhi.ui.view

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/**
 * 按压反馈，对应设计稿 .pressable:active { transform: scale(0.94); opacity: 0.85 }。
 *
 * 列表行用 ?attr/selectableItemBackground 即可；图标按钮与卡片按钮需要
 * 缩放反馈，用本辅助类统一挂载。
 */
object Pressable {

    private const val SCALE_DOWN = 0.94f
    private const val ALPHA_DOWN = 0.85f
    private const val DURATION_MS = 120L

    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(SCALE_DOWN).scaleY(SCALE_DOWN)
                    .alpha(ALPHA_DOWN)
                    .setDuration(DURATION_MS)
                    .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f).scaleY(1f)
                    .alpha(1f)
                    .setDuration(DURATION_MS)
                    .start()
            }
            // 返回 false：不消费事件，交给 OnClickListener 处理点击
            false
        }
    }
}
