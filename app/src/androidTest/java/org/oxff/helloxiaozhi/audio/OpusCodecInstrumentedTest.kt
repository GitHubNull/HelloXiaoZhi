package org.oxff.helloxiaozhi.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * Native libopus 编解码往返测试（需真机/模拟器运行）：
 * 验证 System.loadLibrary 与 960 采样（60ms@16kHz）帧的
 * PCM → Opus → PCM 往返，波形误差 < 5%。
 */
@RunWith(AndroidJUnit4::class)
class OpusCodecInstrumentedTest {

    // 注意：androidTest 会被 DEX 化，方法名不能含空格（反引号命名会导致 DEX 构建失败）
    @Test
    fun testLoadLibraryAnd16kSineRoundtrip() {
        val sampleRate = 16000
        val frame = ShortArray(OpusCodec.FRAME_SIZE)
        for (i in frame.indices) {
            frame[i] = (sin(2 * Math.PI * 440 * i / sampleRate) * 12000).toInt().toShort()
        }

        val encoder = OpusCodec.encoder(sampleRate, 1)
        val decoder = OpusCodec.decoder(sampleRate, 1)
        try {
            val encoded = encoder.encode(frame)
            assertNotNull("Opus 编码结果不应为空", encoded)
            encoded!!
            assertTrue("编码数据应显著小于 PCM（压缩有效）", encoded.size < frame.size * 2)

            val decoded = decoder.decode(encoded)
            assertNotNull("解码结果不应为空", decoded)
            assertEquals("解码帧长应保持 960 采样", frame.size, decoded!!.size)

            // 诊断输出（真机调试用，测试失败时可通过 logcat 查看）
            var inRms = 0.0
            var outRms = 0.0
            for (i in frame.indices) {
                inRms += frame[i].toDouble() * frame[i]
                outRms += decoded[i].toDouble() * decoded[i]
            }
            android.util.Log.i(
                "OpusRoundtrip",
                "DIAG encoded=${encoded.size}B inRms=${Math.sqrt(inRms / frame.size)} " +
                    "outRms=${Math.sqrt(outRms / frame.size)} " +
                    "decoded[0..9]=${decoded.take(10).joinToString(",")}",
            )

            // Opus 编解码有 ~26.5ms 固有算法延迟，逐采样对比会因相位错位而失败。
            // 先通过互相关找到最佳对齐偏移，再验证波形保真度。
            val maxLag = sampleRate * 40 / 1000 // 搜索 ±40ms 足够覆盖算法延迟
            var bestLag = 0
            var bestCorr = -1.0
            var bestRmsRatio = 0.0
            for (lag in 0..maxLag) {
                var sxy = 0.0
                var sxx = 0.0
                var syy = 0.0
                for (i in 0 until frame.size - maxLag) {
                    val j = i + lag
                    if (j >= frame.size) break
                    sxy += frame[i].toDouble() * decoded[j]
                    sxx += frame[i].toDouble() * frame[i]
                    syy += decoded[j].toDouble() * decoded[j]
                }
                val corr = sxy / Math.sqrt(sxx * syy)
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestLag = lag
                    bestRmsRatio = Math.sqrt(syy / sxx)
                }
            }
            assertTrue(
                "对齐后相关系数 $bestCorr (lag=$bestLag) 应 > 0.99",
                bestCorr > 0.99,
            )

            // 对齐后重叠区域的 RMS 能量误差 < 10%（幅度保真）
            assertTrue(
                "对齐后 RMS 能量比 $bestRmsRatio 应在 0.9 ~ 1.1 之间",
                bestRmsRatio > 0.9 && bestRmsRatio < 1.1,
            )
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun test24kDecoderFollowsServerSampleRate() {
        val sampleRate = 24000
        val frame = ShortArray(sampleRate * 60 / 1000) // 1440 采样
        for (i in frame.indices) {
            frame[i] = (sin(2 * Math.PI * 440 * i / sampleRate) * 8000).toInt().toShort()
        }

        val encoder = OpusCodec.encoder(sampleRate, 1)
        val decoder = OpusCodec.decoder(sampleRate, 1)
        try {
            val encoded = encoder.encode(frame)
            assertNotNull("24kHz 编码结果不应为空", encoded)
            // 24kHz 下 60ms 帧为 1440 采样
            val decoded = decoder.decode(encoded!!, sampleRate * 60 / 1000)
            assertNotNull("24kHz 解码结果不应为空", decoded)
            assertTrue("24kHz 解码应产生有效采样", decoded!!.isNotEmpty())
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun testSilenceFrameRoundtrip() {
        val encoder = OpusCodec.encoder()
        val decoder = OpusCodec.decoder()
        try {
            val encoded = encoder.encode(ShortArray(OpusCodec.FRAME_SIZE))
            assertNotNull("静音帧编码结果不应为空", encoded)
            val decoded = decoder.decode(encoded!!)
            assertNotNull("静音帧解码结果不应为空", decoded)
            assertEquals(OpusCodec.FRAME_SIZE, decoded!!.size)
            // Opus 为有损编码，静音帧解码后可残留 ±1~2 量化噪声，不要求逐采样为 0
            var maxAbs = 0
            for (sample in decoded) {
                maxAbs = maxOf(maxAbs, kotlin.math.abs(sample.toInt()))
            }
            assertTrue("静音帧解码噪声 $maxAbs 应 <= 2", maxAbs <= 2)
        } finally {
            encoder.close()
            decoder.close()
        }
    }
}
