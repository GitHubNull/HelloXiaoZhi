package org.oxff.helloxiaozhi.ui.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R

/**
 * 气泡与头像背景工厂。
 *
 * 为什么用代码而不是 shape XML：设计稿的气泡是**非对称圆角**
 * （`border-radius: 20px 20px 6px 20px`），而 `<corners>` 的
 * `bottomLeftRadius`/`bottomRightRadius` 在 GradientDrawable 的 XML 解析中长期
 * 存在互换缺陷；`GradientDrawable.cornerRadii` 的数组顺序
 * （TL, TR, BR, BL，各含 x/y）则是明确无歧义的。用户气泡还需要渐变填充，
 * 两者合并在此处一并构造。
 */
object BubbleDrawables {

    /** 对话详情：用户气泡（--grad-user + 20/20/6/20） */
    fun chatUser(context: Context): GradientDrawable =
        gradientBubble(context, RADIUS_CHAT, RADIUS_CHAT_TIP, tipAtBottomEnd = true)

    /** 对话详情：AI 气泡（--bg-bubble-ai + 描边 + 20/20/20/6） */
    fun chatAi(context: Context): GradientDrawable =
        solidBubble(context, RADIUS_CHAT, RADIUS_CHAT_TIP, tipAtBottomEnd = false)

    /** 通话页历史：用户气泡（14/14/4/14） */
    fun callUser(context: Context): GradientDrawable =
        gradientBubble(context, RADIUS_CALL, RADIUS_CALL_TIP, tipAtBottomEnd = true)

    /** 通话页历史：AI 气泡（14/14/14/4） */
    fun callAi(context: Context): GradientDrawable =
        solidBubble(context, RADIUS_CALL, RADIUS_CALL_TIP, tipAtBottomEnd = false)

    /**
     * 机器人头像圆角方块（.chat-avatar / .bot-avatar），渐变取自
     * [AvatarPalette]，尺寸圆角由调用方按 dimens 传入。
     */
    fun avatar(startColor: Int, endColor: Int, cornerRadiusPx: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }

    private fun gradientBubble(
        context: Context,
        radiusDp: Float,
        tipDp: Float,
        tipAtBottomEnd: Boolean,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            ContextCompat.getColor(context, R.color.xz_grad_user_start),
            ContextCompat.getColor(context, R.color.xz_grad_user_end),
        ),
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = radii(context, radiusDp, tipDp, tipAtBottomEnd)
    }

    private fun solidBubble(
        context: Context,
        radiusDp: Float,
        tipDp: Float,
        tipAtBottomEnd: Boolean,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(ContextCompat.getColor(context, R.color.xz_bg_bubble_ai))
        setStroke(
            dp(context, 1f).toInt(),
            ContextCompat.getColor(context, R.color.xz_border_subtle),
        )
        cornerRadii = radii(context, radiusDp, tipDp, tipAtBottomEnd)
    }

    /** cornerRadii 顺序：TLx, TLy, TRx, TRy, BRx, BRy, BLx, BLy */
    private fun radii(
        context: Context,
        radiusDp: Float,
        tipDp: Float,
        tipAtBottomEnd: Boolean,
    ): FloatArray {
        val r = dp(context, radiusDp)
        val tip = dp(context, tipDp)
        val br = if (tipAtBottomEnd) tip else r
        val bl = if (tipAtBottomEnd) r else tip
        return floatArrayOf(r, r, r, r, br, br, bl, bl)
    }

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /** .msg-bubble { border-radius: 20px … } */
    private const val RADIUS_CHAT = 20f

    /** 气泡尖角侧（用户 = bottom-end，AI = bottom-start） */
    private const val RADIUS_CHAT_TIP = 6f

    /** .history-bubble { border-radius: 14px … } */
    private const val RADIUS_CALL = 14f
    private const val RADIUS_CALL_TIP = 4f
}
