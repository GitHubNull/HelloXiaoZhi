package org.oxff.helloxiaozhi.ui.view

import android.content.Context
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R

/**
 * 机器人头像调色板，对应设计稿 add-bot-modal.js 的 AVATAR_COLORS
 * （8 组 135° 线性渐变）。
 *
 * 持久化保存的是**索引**而非 CSS 渐变字符串：设计稿的 mock 直接把
 * `linear-gradient(...)` 存进 storage，那是浏览器专有的表达，落到 Android
 * 上必须换成与平台无关的索引，渲染时再解析成 GradientDrawable。
 */
object AvatarPalette {

    /** 渐变组数（与设计稿一致） */
    const val SIZE = 8

    private val STARTS = intArrayOf(
        R.color.xz_avatar_0_start,
        R.color.xz_avatar_1_start,
        R.color.xz_avatar_2_start,
        R.color.xz_avatar_3_start,
        R.color.xz_avatar_4_start,
        R.color.xz_avatar_5_start,
        R.color.xz_avatar_6_start,
        R.color.xz_avatar_7_start,
    )

    private val ENDS = intArrayOf(
        R.color.xz_avatar_0_end,
        R.color.xz_avatar_1_end,
        R.color.xz_avatar_2_end,
        R.color.xz_avatar_3_end,
        R.color.xz_avatar_4_end,
        R.color.xz_avatar_5_end,
        R.color.xz_avatar_6_end,
        R.color.xz_avatar_7_end,
    )

    /**
     * 把任意持久化索引归一到有效区间。
     * 存量数据迁移或手工改档可能带来越界值，这里回绕而不是抛异常。
     */
    fun normalize(index: Int): Int = ((index % SIZE) + SIZE) % SIZE

    fun startColor(context: Context, index: Int): Int =
        ContextCompat.getColor(context, STARTS[normalize(index)])

    fun endColor(context: Context, index: Int): Int =
        ContextCompat.getColor(context, ENDS[normalize(index)])

    /** 构造圆角方块头像背景（.chat-avatar / .bot-avatar / .wake-target-avatar） */
    fun avatarBackground(context: Context, index: Int, cornerRadiusPx: Float) =
        BubbleDrawables.avatar(
            startColor(context, index),
            endColor(context, index),
            cornerRadiusPx,
        )
}
