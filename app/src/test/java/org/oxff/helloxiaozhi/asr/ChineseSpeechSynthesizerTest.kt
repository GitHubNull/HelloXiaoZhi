package org.oxff.helloxiaozhi.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.oxff.helloxiaozhi.util.AudioMath

/**
 * ChineseSpeechSynthesizer 中文语音合成器单元测试：
 * 时长计算、句首轻声包络、WAV 读写往返。
 */
class ChineseSpeechSynthesizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `合成时长符合预期 两字加句末静音`() {
        val result = ChineseSpeechSynthesizer.synthesize(
            ChineseSpeechSynthesizer.SpeechParams(text = "你好")
        )
        // 2 字 × 200ms + 1 停顿 × 60ms + 1500ms 尾静音 = 1960ms
        assertEquals(1960, result.durationMs)
        assertEquals(2, result.charIntervals.size)
        assertEquals('你', result.charIntervals[0].char)
    }

    @Test
    fun `句首轻声显著降低首字电平`() {
        // 首字轻声 0.3：首字帧电平应远低于后字
        val soft = ChineseSpeechSynthesizer.synthesize(
            ChineseSpeechSynthesizer.SpeechParams(text = "你好明天", initialSoftness = 0.3f)
        )
        val normal = ChineseSpeechSynthesizer.synthesize(
            ChineseSpeechSynthesizer.SpeechParams(text = "你好明天", initialSoftness = 1.0f)
        )

        val firstCharEndSample = soft.charIntervals[0].endMs * ChineseSpeechSynthesizer.SAMPLE_RATE / 1000
        val softHeadLevel = AudioMath.rmsLevel(soft.pcm.copyOfRange(0, firstCharEndSample))
        val normalHeadLevel = AudioMath.rmsLevel(normal.pcm.copyOfRange(0, firstCharEndSample))

        assertTrue("轻声首字电平应低于正常（$softHeadLevel < $normalHeadLevel）",
            softHeadLevel < normalHeadLevel * 0.6f)
    }

    @Test
    fun `有声段电平高于 VAD 阈值 静音段低于阈值`() {
        val result = ChineseSpeechSynthesizer.synthesize(
            ChineseSpeechSynthesizer.SpeechParams(text = "今天天气")
        )
        val voicedFrame = result.pcm.copyOfRange(960, 1920)  // 第一个字中段
        val tailStart = (result.durationMs - 500) * ChineseSpeechSynthesizer.SAMPLE_RATE / 1000
        val tailFrame = result.pcm.copyOfRange(tailStart, tailStart + 960)

        assertTrue(AudioMath.rmsLevel(voicedFrame) > 0.02f)
        assertEquals(0f, AudioMath.rmsLevel(tailFrame), 1e-6f)
    }

    @Test
    fun `WAV 读写往返一致`() {
        val pcm = ChineseSpeechSynthesizer.synthesizePcm("你好")
        val wav = tempFolder.newFile("roundtrip.wav")
        ChineseSpeechSynthesizer.saveAsWav(pcm, wav)

        val loaded = ChineseSpeechSynthesizer.loadWav(wav)
        assertEquals(pcm.size, loaded.size)
        for (i in pcm.indices) {
            assertEquals(pcm[i], loaded[i])
        }
    }
}
