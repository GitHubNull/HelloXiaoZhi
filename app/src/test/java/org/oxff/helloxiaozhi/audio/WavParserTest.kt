package org.oxff.helloxiaozhi.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV 解析器单元测试：RIFF 魔数识别与 16bit PCM 数据块提取。
 * 用于自定义服务器（代理）模式的二进制帧分流。
 */
class WavParserTest {

    @Test
    fun `RIFF 魔数识别为 WAV`() {
        val wav = buildWav(sampleRate = 16000, samples = shortArrayOf(100, -100, 200))
        assertTrue(WavParser.isWav(wav))
    }

    @Test
    fun `原始 Opus 帧不识别为 WAV`() {
        // Opus 包通常以 0xF9/0xF8 等 TOC 字节开头
        val opus = byteArrayOf(0xF9.toByte(), 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x1, 0x2, 0x3, 0x4)
        assertFalse(WavParser.isWav(opus))
    }

    @Test
    fun `过短数据不识别为 WAV`() {
        assertFalse(WavParser.isWav(byteArrayOf('R'.code.toByte(), 'I'.code.toByte())))
        assertFalse(WavParser.isWav(ByteArray(0)))
    }

    @Test
    fun `解析采样率与 PCM 采样`() {
        val samples = shortArrayOf(1, -2, 3, -4)
        val wav = buildWav(sampleRate = 16000, samples = samples)

        val parsed = WavParser.parse(wav)
        assertTrue(parsed != null)
        assertEquals(16000, parsed!!.first)
        assertEquals(4, parsed.second.size)
        assertEquals(1, parsed.second[0].toInt())
        assertEquals(-2, parsed.second[1].toInt())
        assertEquals(3, parsed.second[2].toInt())
        assertEquals(-4, parsed.second[3].toInt())
    }

    @Test
    fun `24kHz 采样率正确解析`() {
        val parsed = WavParser.parse(buildWav(sampleRate = 24000, samples = shortArrayOf(0)))
        assertTrue(parsed != null)
        assertEquals(24000, parsed!!.first)
    }

    @Test
    fun `非 16bit WAV 返回 null`() {
        val wav = buildWav(sampleRate = 16000, samples = shortArrayOf(0), bitsPerSample = 8)
        assertNull(WavParser.parse(wav))
    }

    @Test
    fun `损坏数据返回 null`() {
        val wav = buildWav(sampleRate = 16000, samples = shortArrayOf(0))
        val truncated = wav.copyOf(wav.size / 2)
        assertNull(WavParser.parse(truncated))
    }

    /** 构造最小 WAV：44 字节标准头 + PCM 数据 */
    private fun buildWav(
        sampleRate: Int,
        samples: ShortArray,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = samples.size * bitsPerSample / 8

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)                       // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        if (bitsPerSample == 16) {
            for (s in samples) {
                buffer.putShort(s)
            }
        } else {
            // 8bit PCM：每采样 1 字节（无符号）
            for (s in samples) {
                buffer.put((s.toInt() shr 8 and 0xFF).toByte())
            }
        }
        return buffer.array()
    }
}
