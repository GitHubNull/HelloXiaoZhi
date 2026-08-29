package org.oxff.helloxiaozhi.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioFingerprint 音频指纹单元测试。
 */
class AudioFingerprintTest {

    private fun sinePcm(frames: Int, amplitude: Int): ShortArray {
        val pcm = ShortArray(frames * AudioFingerprint.FRAME_SIZE)
        for (i in pcm.indices) {
            pcm[i] = (amplitude * Math.sin(2 * Math.PI * 440 * i / 16000.0)).toInt().toShort()
        }
        return pcm
    }

    @Test
    fun `相同音频指纹可复现`() {
        val pcm = sinePcm(10, 8000)
        assertEquals(AudioFingerprint.fromPcm(pcm), AudioFingerprint.fromPcm(pcm.copyOf()))
    }

    @Test
    fun `帧数与电平差异可区分`() {
        val short = AudioFingerprint.fromPcm(sinePcm(5, 8000))
        val long = AudioFingerprint.fromPcm(sinePcm(10, 8000))
        val quiet = AudioFingerprint.fromPcm(sinePcm(10, 2000))

        assertFalse(short.matches(long))
        assertFalse(long.matches(quiet))
        assertTrue(long.matches(AudioFingerprint.fromPcm(sinePcm(10, 8000))))
    }

    @Test
    fun `容差范围内匹配`() {
        val base = AudioFingerprint.fromPcm(sinePcm(10, 8000))
        // 少 1 帧在容差（±2）内
        val near = base.copy(frameCount = base.frameCount - 1)
        assertTrue(base.matches(near))
        // 电平差 0.04 在容差（±0.05）内
        val nearLevel = base.copy(averageLevel = base.averageLevel + 0.04f)
        assertTrue(base.matches(nearLevel))
    }

    @Test
    fun `空音频指纹为全零`() {
        val fp = AudioFingerprint.fromPcm(ShortArray(0))
        assertEquals(0, fp.frameCount)
        assertEquals(0f, fp.averageLevel, 1e-6f)
        assertEquals(0, fp.spectralHash)
    }

    @Test
    fun `不同内容频谱哈希不同`() {
        val a = AudioFingerprint.fromPcm(sinePcm(10, 8000))
        // 方波与正弦波的过零率/包络不同
        val square = ShortArray(10 * AudioFingerprint.FRAME_SIZE) {
            if (it / 20 % 2 == 0) 8000 else -8000
        }
        val b = AudioFingerprint.fromPcm(square)
        assertNotEquals(a.spectralHash, b.spectralHash)
    }
}
