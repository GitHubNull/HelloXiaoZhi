package org.oxff.helloxiaozhi.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 上行语音增强器单元测试（AGC + 噪声门）。
 *
 * 核心验收目标：
 *  - 轻声/悄悄话帧被放大到可触发服务器端 VAD 的电平（逼近目标电平，
 *    增益不超过 +24dB 上限）；
 *  - 大声说话不削波、不被过度放大；
 *  - 持续底噪被噪声门衰减，不随 AGC 放大；
 *  - 帧间增益平滑（无泵浦突变）；
 *  - reset 清空全部状态。
 */
class MicEnhancerTest {

    /** 生成峰值电平为 peak 的伪语音帧（交变极性，模拟有声信号） */
    private fun frame(peak: Float, size: Int = 960): ShortArray {
        val amp = (peak * 32767f).toInt().coerceIn(0, 32767).toShort()
        return ShortArray(size) { i -> if (i % 2 == 0) amp else (-amp.toInt()).toShort() }
    }

    /** 帧峰值电平（0..1） */
    private fun peakOf(frame: ShortArray): Float =
        frame.maxOf { abs(it.toInt()) } / 32768f

    /** 喂入 count 帧固定电平，返回最后一帧输出 */
    private fun feed(enhancer: MicEnhancer, peak: Float, count: Int): ShortArray {
        var out = ShortArray(0)
        repeat(count) { out = enhancer.process(frame(peak)) }
        return out
    }

    @Test
    fun `轻声帧被放大逼近目标电平且增益不超上限`() {
        val enhancer = MicEnhancer()
        // 先建立底噪环境（模拟通话开始时的安静背景）
        feed(enhancer, 0.001f, 30)
        // 悄悄话：峰值约 0.02（原需 45%~55% 音量，轻声约为其 1/8~1/16）
        val out = feed(enhancer, 0.02f, 10)
        val outPeak = peakOf(out)
        // 期望增益 0.3/0.02 = 15，低于 +24dB 上限（≈15.85），输出逼近目标 0.3
        assertTrue("轻声输出应逼近目标电平: $outPeak", outPeak > 0.2f && outPeak < 0.35f)
        assertTrue("轻声放大约 ${enhancer.lastGain} 倍，应远大于 1", enhancer.lastGain > 8f)
        assertTrue("增益不得超过 +24dB 上限", enhancer.lastGain <= MicEnhancer.dbToFactor(24f) + 0.01f)
    }

    @Test
    fun `极轻帧受最大增益约束`() {
        val enhancer = MicEnhancer()
        feed(enhancer, 0.001f, 30)
        // 极轻输入：期望增益 0.3/0.005 = 60 > 上限 15.85，应被截断
        val out = feed(enhancer, 0.005f, 15)
        val maxGain = MicEnhancer.dbToFactor(24f)
        assertTrue("增益应截断在最大增益附近: ${enhancer.lastGain}",
            enhancer.lastGain <= maxGain + 0.01f && enhancer.lastGain > maxGain * 0.9f)
        assertTrue("输出不应超过 输入×最大增益",
            peakOf(out) <= 0.005f * maxGain + 0.01f)
    }

    @Test
    fun `大声说话不削波且不被放大`() {
        val enhancer = MicEnhancer()
        feed(enhancer, 0.001f, 20)
        val inPeak = 0.9f
        val out = feed(enhancer, inPeak, 15)
        val outPeak = peakOf(out)
        // 大声应被衰减向目标电平，绝不放大、绝不削波
        assertTrue("大声输出应低于输入: in=$inPeak out=$outPeak", outPeak < inPeak)
        assertTrue("输出不应削波", out.all { it.toInt() in -32768..32767 })
        assertTrue("大声输出接近目标电平量级: $outPeak", outPeak < 0.6f)
    }

    @Test
    fun `底噪帧被噪声门衰减`() {
        val enhancer = MicEnhancer()
        // 持续噪声建立底噪
        feed(enhancer, 0.005f, 30)
        assertTrue("底噪应已建立: ${enhancer.currentNoiseFloor}",
            enhancer.currentNoiseFloor > 0.003f)
        // 低于底噪的帧应被门控衰减到约 0.1 倍
        val out = enhancer.process(frame(0.002f))
        assertEquals("门下总增益应约为门控系数", 0.1f, enhancer.lastGain, 0.02f)
        assertTrue("噪声输出应远小于输入", peakOf(out) < 0.002f * 0.15f)
    }

    @Test
    fun `底噪不被持续轻声抬高`() {
        val enhancer = MicEnhancer()
        feed(enhancer, 0.001f, 30)
        val floorBefore = enhancer.currentNoiseFloor
        // 持续轻声不应被误学成底噪（底噪跟踪只吃低于起始阈值的帧）
        feed(enhancer, 0.02f, 50)
        assertEquals("轻声期间底噪不应上抬", floorBefore, enhancer.currentNoiseFloor, floorBefore * 0.2f)
    }

    @Test
    fun `语音结束后增益释放平滑无泵浦`() {
        val enhancer = MicEnhancer()
        feed(enhancer, 0.001f, 10)
        feed(enhancer, 0.3f, 8)
        // 语音停止后连续轻声帧：释放路径每帧变化不超过 15%，不允许硬切断
        var prev = enhancer.lastGain
        repeat(12) {
            enhancer.process(frame(0.01f))
            assertTrue("增益释放应平滑: $prev -> ${enhancer.lastGain}",
                enhancer.lastGain >= prev * 0.8f)
            prev = enhancer.lastGain
        }
    }

    @Test
    fun `reset 清空全部状态`() {
        val enhancer = MicEnhancer()
        feed(enhancer, 0.005f, 30)
        feed(enhancer, 0.3f, 10)
        enhancer.reset()
        assertEquals(0f, enhancer.currentNoiseFloor, 0f)
        assertEquals(0f, enhancer.currentEstPeak, 0f)
        assertEquals(1f, enhancer.lastGain, 0f)
    }

    @Test
    fun `dB 转线性系数`() {
        assertEquals(1f, MicEnhancer.dbToFactor(0f), 0.001f)
        assertEquals(15.849f, MicEnhancer.dbToFactor(24f), 0.01f)
        assertEquals(0.2512f, MicEnhancer.dbToFactor(-12f), 0.001f)
    }
}
