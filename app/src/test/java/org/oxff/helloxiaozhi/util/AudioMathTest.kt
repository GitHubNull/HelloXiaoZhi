package org.oxff.helloxiaozhi.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VAD 电平计算单元测试（对应 Web 端 AudioManager.detectAudioLevel）。
 */
class AudioMathTest {

    @Test
    fun `静音帧电平为 0`() {
        assertEquals(0f, AudioMath.rmsLevel(ShortArray(960)), 0f)
    }

    @Test
    fun `满幅帧电平为 1`() {
        val frame = ShortArray(960) { Short.MAX_VALUE }
        assertEquals(1f, AudioMath.rmsLevel(frame), 0.001f)
    }

    @Test
    fun `半幅帧电平为 0点5`() {
        val frame = ShortArray(960) { (Short.MAX_VALUE / 2).toShort() }
        assertEquals(0.5f, AudioMath.rmsLevel(frame), 0.001f)
    }

    @Test
    fun `负采样按绝对值计算`() {
        val frame = ShortArray(960) { Short.MIN_VALUE.toInt().div(2).toShort() }
        assertEquals(0.5f, AudioMath.rmsLevel(frame), 0.001f)
    }

    @Test
    fun `空帧电平为 0`() {
        assertEquals(0f, AudioMath.rmsLevel(ShortArray(0)), 0f)
    }

    @Test
    fun `超过说话阈值判定（与状态机阈值一致性）`() {
        // 0.05 电平对应 int16 幅值 1638，应高于 THRESHOLD_SPEAKING
        val frame = ShortArray(960) { 1638 }
        val level = AudioMath.rmsLevel(frame)
        assertEquals(0.05f, level, 0.001f)
        assert(level > 0.04f)
    }
}
