package org.oxff.helloxiaozhi.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间展示格式化。
 *
 * 设计稿的 mock 把「昨天」「周一」这类**展示字符串**直接当作数据字段存储
 * （mock.js 的 chat.lastTime），导致会话列表无法排序。这里改为一律持久化
 * epoch 毫秒，展示串在渲染时由 [relative] 现算。
 */
object TimeFormat {

    /** 消息气泡时间：HH:mm */
    fun hhmm(ts: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("HH:mm", locale).format(Date(ts))

    /**
     * 会话列表时间：今天显示 HH:mm，昨天显示「昨天」，一周内显示星期，
     * 更早显示 M月d日。
     *
     * @param yesterdayLabel 由调用方注入本地化的「昨天」，避免此处依赖 Context
     */
    fun relative(
        ts: Long,
        now: Long = System.currentTimeMillis(),
        yesterdayLabel: String = "昨天",
        locale: Locale = Locale.getDefault(),
    ): String {
        val days = dayDiff(ts, now, locale)
        return when {
            days <= 0L -> hhmm(ts, locale)
            days == 1L -> yesterdayLabel
            days < 7L -> SimpleDateFormat("EEEE", locale).format(Date(ts))
            else -> SimpleDateFormat("M月d日", locale).format(Date(ts))
        }
    }

    /**
     * 相差的**日历天数**（而非 24 小时的整数倍）：23:59 与次日 00:01 相差 1 天。
     *
     * 不能用「毫秒差 / 86400000」（夏令时日的实际毫秒数不是 86400000），
     * 也不能用「年差×366 + 年内日差」的近似（跨两个日历年且中间年是 365 天时会多算）。
     * 把较早日期的 Calendar 按天推进到较晚日期并计数，跨年与夏令时都精确。
     */
    private fun dayDiff(ts: Long, now: Long, locale: Locale): Long {
        val a = Calendar.getInstance(locale).apply { timeInMillis = ts }
        val b = Calendar.getInstance(locale).apply { timeInMillis = now }
        var days = 0L
        while (a.get(Calendar.YEAR) != b.get(Calendar.YEAR) ||
            a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)
        ) {
            a.add(Calendar.DAY_OF_YEAR, 1)
            days++
        }
        return days
    }

    /** 通话计时：mm:ss（对应设计稿 XZUtil.fmtDuration） */
    fun duration(seconds: Int): String =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
