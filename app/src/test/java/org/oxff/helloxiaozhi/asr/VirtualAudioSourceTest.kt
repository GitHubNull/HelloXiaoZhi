package org.oxff.helloxiaozhi.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.oxff.helloxiaozhi.util.AudioMath

/**
 * VirtualAudioSource 虚拟音频源单元测试：
 * WAV 文件源、合成信号源、噪声叠加源的帧输出与电平特性。
 */
class VirtualAudioSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `WAV 文件源按 960 采样成帧`() {
        val pcm = ChineseSpeechSynthesizer.synthesizePcm("你好")  // 1960ms = 31360 采样
        val wav = tempFolder.newFile("test.wav")
        ChineseSpeechSynthesizer.saveAsWav(pcm, wav)

        val source = WavFileAudioSource(wav)
        assertEquals(33, source.totalFrames)  // ceil(31360 / 960)

        var frames = 0
        var samples = 0
        var frame = source.readFrame()
        while (frame != null) {
            frames++
            samples += frame.size
            frame = source.readFrame()
        }
        assertEquals(source.totalFrames, frames)
        assertEquals(pcm.size, samples)
        assertNull(source.readFrame())

        source.reset()
        assertNotNull(source.readFrame())
    }

    @Test
    fun `合成源 恒定电平接近设定值`() {
        val source = SyntheticAudioSource(
            SyntheticAudioSource.AudioPattern.CONSTANT_LEVEL, durationMs = 600, baseLevel = 0.1f
        )
        val frame = source.readFrame()!!
        val level = AudioMath.rmsLevel(frame)
        assertEquals(0.1f, level, 0.01f)
    }

    @Test
    fun `合成源 静音电平为 0`() {
        val source = SyntheticAudioSource(
            SyntheticAudioSource.AudioPattern.SILENCE, durationMs = 600
        )
        assertEquals(0f, AudioMath.rmsLevel(source.readFrame()!!), 1e-6f)
    }

    @Test
    fun `合成源 渐增电平单调上升`() {
        val source = SyntheticAudioSource(
            SyntheticAudioSource.AudioPattern.RAMP_UP, durationMs = 1200, baseLevel = 0.2f
        )
        val levels = mutableListOf<Float>()
        var frame = source.readFrame()
        while (frame != null) {
            levels.add(AudioMath.rmsLevel(frame))
            frame = source.readFrame()
        }
        assertTrue("电平应单调不减", levels.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(levels.last() > levels.first())
    }

    @Test
    fun `噪声源叠加后电平升高`() {
        val clean = SyntheticAudioSource(
            SyntheticAudioSource.AudioPattern.SILENCE, durationMs = 600
        )
        val noisy = NoisyAudioSource(clean, noiseLevel = 0.1f, NoisyAudioSource.NoiseType.WHITE)
        val level = AudioMath.rmsLevel(noisy.readFrame()!!)
        assertTrue("白噪声叠加后电平应大于 0（实际 $level）", level > 0.01f)
    }

    @Test
    fun `真实音频源携带字幕`() {
        val pcm = ChineseSpeechSynthesizer.synthesizePcm("你好")
        val wav = tempFolder.newFile("real.wav")
        ChineseSpeechSynthesizer.saveAsWav(pcm, wav)

        val subtitle = SubtitleEntry(0, 1000, "你好")
        val source = RealWorldAudioSource(wav, subtitle)
        assertEquals(subtitle, source.getSubtitle())
        assertTrue(source.description.contains("real.wav"))
    }

    @Test
    fun `空调外机噪声 中低电平低于触发阈值`() {
        // 电平 0.03 的空调外机噪声（真实室内常见强度）不应触发状态机（阈值 0.02）
        val clean = SyntheticAudioSource(
            SyntheticAudioSource.AudioPattern.SILENCE, durationMs = 6000
        )
        val noisy = NoisyAudioSource(clean, noiseLevel = 0.03f, NoisyAudioSource.NoiseType.AC_OUTDOOR)
        val levels = drainLevels(noisy)
        assertTrue("空调噪声应为非静音（实际均值 ${levels.average()}）",
            levels.average() > 0.0005)
        assertTrue("全部帧 RMS 应低于触发阈值 0.02（最大值 ${levels.max()}）",
            levels.all { it < 0.02f })
    }

    @Test
    fun `空调外机噪声 压缩机启停包络`() {
        // 8 秒周期：0-5s 运行（包络 1.0）、5-8s 停机（包络 0.25），停机段电平应明显下降
        val clean = SyntheticAudioSource(
            SyntheticAudioSource.AudioPattern.SILENCE, durationMs = 8000
        )
        val noisy = NoisyAudioSource(clean, noiseLevel = 0.2f, NoisyAudioSource.NoiseType.AC_OUTDOOR)
        val levels = drainLevels(noisy)
        // 每帧 60ms：运行段取 0.48-4.5s，停机段取 5.52-7.5s（避开 320ms 启停斜坡）
        val onRms = levels.subList(8, minOf(75, levels.size)).average()
        val offRms = levels.subList(minOf(92, levels.size), minOf(125, levels.size)).average()
        assertTrue("停机段电平应明显低于运行段（on=$onRms, off=$offRms）",
            offRms < onRms * 0.6)
        assertTrue("停机段仍有残余噪声（包络 0.25 而非静音）", offRms > 0)
    }

    /** 耗尽音频源，逐帧收集 RMS 电平 */
    private fun drainLevels(source: VirtualAudioSource): List<Float> {
        val levels = mutableListOf<Float>()
        var frame = source.readFrame()
        while (frame != null) {
            levels.add(AudioMath.rmsLevel(frame))
            frame = source.readFrame()
        }
        return levels
    }
}
