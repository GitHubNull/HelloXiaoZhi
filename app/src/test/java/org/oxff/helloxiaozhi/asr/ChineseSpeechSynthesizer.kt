package org.oxff.helloxiaozhi.asr

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 中文语音合成器（测试用简化模型）。
 *
 * 不追求真实发音，而是生成「电平包络贴近真实中文语音」的模拟信号，
 * 用于可控复现状态机 / VAD 的关键场景：
 *  - 每个汉字对应一个有声段（voiced），振幅随声调模式变化
 *  - 句首轻声：首字振幅乘 [initialSoftness]（0.3 模拟"你/今"类轻声字）
 *  - 字间短暂停顿（约 60ms），句末拖尾静音
 *
 * 生成的 PCM 与真实麦克风输入同格式：16kHz / 16bit / 单声道 / 60ms 帧。
 */
object ChineseSpeechSynthesizer {

    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE = 960  // 60ms @ 16kHz

    /** 单个字的发音时长（默认 200ms，约 5 字/秒语速） */
    const val DEFAULT_CHAR_MS = 200

    /** 字间停顿时长（60ms，小于一帧，模拟连续语流） */
    const val PAUSE_MS = 60

    /** 句末静音时长（供状态机静音检测收尾） */
    const val TAIL_SILENCE_MS = 1500

    /**
     * 合成参数
     *
     * @param text 文本（决定有声段数量）
     * @param initialSoftness 句首轻声强度 0..1（作用于首字振幅，0.3 = 明显轻声）
     * @param baseAmplitude 基准振幅 0..1（相对满幅）
     * @param charMs 单字时长（毫秒）
     * @param f0 基频（Hz），模拟男声 ~120 / 女声 ~220
     * @param includeTailSilence 是否附加句末静音（端到端测试需要，触发静音计时）
     */
    data class SpeechParams(
        val text: String,
        val initialSoftness: Float = 1.0f,
        val baseAmplitude: Float = 0.35f,
        val charMs: Int = DEFAULT_CHAR_MS,
        val f0: Int = 150,
        val includeTailSilence: Boolean = true
    )

    /** 合成结果：PCM 采样 + 每个字的时间区间（用于对齐验证） */
    data class SynthesisResult(
        val pcm: ShortArray,
        val charIntervals: List<CharInterval>
    ) {
        val durationMs: Int get() = pcm.size * 1000 / SAMPLE_RATE
        val totalFrames: Int get() = (pcm.size + FRAME_SIZE - 1) / FRAME_SIZE
    }

    data class CharInterval(val char: Char, val startMs: Int, val endMs: Int)

    /** 按参数合成语音 */
    fun synthesize(params: SpeechParams): SynthesisResult {
        val chars = params.text.toList().filter { !it.isWhitespace() }
        require(chars.isNotEmpty()) { "合成文本不能为空" }

        val charSamples = params.charMs * SAMPLE_RATE / 1000
        val pauseSamples = PAUSE_MS * SAMPLE_RATE / 1000
        val tailSamples = if (params.includeTailSilence) TAIL_SILENCE_MS * SAMPLE_RATE / 1000 else 0

        val totalSamples = chars.size * charSamples + (chars.size - 1) * pauseSamples + tailSamples
        val pcm = ShortArray(totalSamples)
        val intervals = mutableListOf<CharInterval>()

        var samplePos = 0
        var phase = 0.0
        for ((index, c) in chars.withIndex()) {
            val startMs = samplePos * 1000 / SAMPLE_RATE

            // 声调模式：四声 + 轻声的简化振幅/频率包络
            val tone = toneOf(c, index)
            val softness = if (index == 0) params.initialSoftness else 1.0f
            val amplitude = params.baseAmplitude * softness

            for (i in 0 until charSamples) {
                val progress = i.toDouble() / charSamples
                // 起音/收音斜坡（10%），避免方波爆音导致电平计算失真
                val attackDecay = minOf(1.0, progress / 0.1, (1 - progress) / 0.1).coerceAtLeast(0.0)
                val f = frequencyAt(tone, params.f0, progress)
                phase += 2 * Math.PI * f / SAMPLE_RATE
                val sample = amplitude * attackDecay * Math.sin(phase) * amplitudeEnvelope(tone, progress)
                pcm[samplePos + i] = (sample * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            samplePos += charSamples

            val endMs = samplePos * 1000 / SAMPLE_RATE
            intervals.add(CharInterval(c, startMs, endMs))

            if (index < chars.size - 1) samplePos += pauseSamples
        }
        // 其余部分保持默认 0（静音）

        return SynthesisResult(pcm, intervals)
    }

    /** 快速合成并返回 PCM（便捷方法） */
    fun synthesizePcm(
        text: String,
        initialSoftness: Float = 1.0f,
        baseAmplitude: Float = 0.35f
    ): ShortArray = synthesize(
        SpeechParams(text = text, initialSoftness = initialSoftness, baseAmplitude = baseAmplitude)
    ).pcm

    /** 将 PCM 保存为 16kHz/16bit/单声道 WAV 文件（用于生成预录测试资源） */
    fun saveAsWav(pcm: ShortArray, file: File) {
        file.parentFile?.mkdirs()
        val dataSize = pcm.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF 头
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        // fmt 块
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)                    // 块大小
        buffer.putShort(1)                   // PCM 格式
        buffer.putShort(1)                   // 单声道
        buffer.putInt(SAMPLE_RATE)           // 采样率
        buffer.putInt(SAMPLE_RATE * 2)       // 字节率
        buffer.putShort(2)                   // 块对齐
        buffer.putShort(16)                  // 位深
        // data 块
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        for (s in pcm) buffer.putShort(s)

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(buffer.array())
        }
    }

    /** 读取 WAV 文件为 PCM（供测试资源验证） */
    fun loadWav(file: File): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF") { "非法 WAV 文件: $file" }
        val shorts = ShortArray((bytes.size - 44) / 2)
        ByteBuffer.wrap(bytes, 44, bytes.size - 44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return shorts
    }

    // ---------------- 声调模型（简化） ----------------

    /** 声调类别：按字符哈希稳定映射，保证同一文本合成结果可复现 */
    private enum class Tone { TONE1, TONE2, TONE3, TONE4, NEUTRAL }

    private fun toneOf(c: Char, index: Int): Tone {
        // 常见轻声字（"你/吗/呢/吧/的/了"等出现在词尾或句首时按轻声处理）
        val neutralChars = setOf('吗', '呢', '吧', '的', '了', '啊', '么')
        if (c in neutralChars) return Tone.NEUTRAL
        return when (c.code % 5) {
            0 -> Tone.TONE1
            1 -> Tone.TONE2
            2 -> Tone.TONE3
            3 -> Tone.TONE4
            else -> Tone.NEUTRAL
        }
    }

    /** 声调的音高曲线（F0 倍率，progress 0..1） */
    private fun frequencyAt(tone: Tone, f0: Int, progress: Double): Double {
        val factor = when (tone) {
            Tone.TONE1 -> 1.3                       // 一声：高平
            Tone.TONE2 -> 1.0 + 0.4 * progress      // 二声：中升
            Tone.TONE3 -> 0.9 - 0.2 * Math.sin(Math.PI * progress) // 三声：降升（简化为凹）
            Tone.TONE4 -> 1.4 - 0.5 * progress      // 四声：全降
            Tone.NEUTRAL -> 0.8                     // 轻声：短而低
        }
        return f0 * factor
    }

    /** 声调的振幅包络（轻声更短促低弱） */
    private fun amplitudeEnvelope(tone: Tone, progress: Double): Double = when (tone) {
        Tone.NEUTRAL -> 0.5
        Tone.TONE3 -> 0.7 + 0.3 * progress
        else -> 1.0
    }
}
