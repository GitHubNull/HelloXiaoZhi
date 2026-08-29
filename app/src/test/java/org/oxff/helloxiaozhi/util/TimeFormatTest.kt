package org.oxff.helloxiaozhi.util

import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 时间展示格式化单元测试。
 *
 * 会话列表的时间展示规则（今天 HH:mm / 昨天 / 一周内星期 / 更早日期）是
 * 设计稿 mock 里硬编码字符串的正确性来源，这里把边界钉死。
 */
class TimeFormatTest {

    private val locale = Locale.CHINA

    /** 固定一个参考时刻：2026-08-28（周五）15:30 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(locale).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `今天的消息显示 HH mm`() {
        val now = at(2026, 8, 28, 15, 30)
        val ts = at(2026, 8, 28, 9, 5)

        assertEquals("09:05", TimeFormat.relative(ts, now, locale = locale))
    }

    @Test
    fun `跨午夜只过两分钟也算昨天`() {
        val now = at(2026, 8, 28, 0, 1)
        val ts = at(2026, 8, 27, 23, 59)

        assertEquals("昨天", TimeFormat.relative(ts, now, locale = locale))
    }

    @Test
    fun `一周内的消息显示星期`() {
        val now = at(2026, 8, 28, 15, 30) // 周五
        val ts = at(2026, 8, 24, 10, 0) // 周一

        assertEquals("星期一", TimeFormat.relative(ts, now, locale = locale))
    }

    @Test
    fun `满七天后显示月日`() {
        val now = at(2026, 8, 28, 15, 30)
        val ts = at(2026, 8, 20, 10, 0)

        assertEquals("8月20日", TimeFormat.relative(ts, now, locale = locale))
    }

    @Test
    fun `跨年仅差一天时显示昨天`() {
        val now = at(2026, 1, 1, 0, 1)
        val ts = at(2025, 12, 31, 23, 59)

        // 日历上只差一天，应显示「昨天」而非跨年日期
        assertEquals("昨天", TimeFormat.relative(ts, now, locale = locale))
    }

    @Test
    fun `跨两个日历年仍按实际天数计算`() {
        // 2025-12-31 到 2027-01-01 实际相差 366 天（2026 是平年 365 天 + 1），
        // 远超一周，应显示月日而非星期
        val now = at(2027, 1, 1, 12, 0)
        val ts = at(2025, 12, 31, 12, 0)

        assertEquals("12月31日", TimeFormat.relative(ts, now, locale = locale))
    }

    @Test
    fun `hhmm 补零`() {
        assertEquals("08:07", TimeFormat.hhmm(at(2026, 8, 28, 8, 7), locale))
    }

    @Test
    fun `通话计时格式化为 mm ss`() {
        assertEquals("00:00", TimeFormat.duration(0))
        assertEquals("00:05", TimeFormat.duration(5))
        assertEquals("01:00", TimeFormat.duration(60))
        assertEquals("12:34", TimeFormat.duration(754))
        assertEquals("99:59", TimeFormat.duration(5999))
    }
}
