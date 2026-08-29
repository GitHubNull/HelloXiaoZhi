package org.oxff.helloxiaozhi.asr

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 虚拟音频源：替代真实麦克风，提供可控的 PCM 音频输入。
 * 支持：预录 WAV 文件、合成语音、静音、噪声、电平渐变等场景。
 */
interface VirtualAudioSource {
    /** 读取一帧 PCM 数据（60ms @ 16kHz = 960 采样） */
    fun readFrame(): ShortArray?

    /** 重置到音频开头 */
    fun reset()

    /** 当前音频的总帧数 */
    val totalFrames: Int

    /** 音频描述（用于报告） */
    val description: String
}

/** 字幕条目 */
data class SubtitleEntry(
    val startMs: Int,
    val endMs: Int,
    val text: String
)

/** 从 WAV 文件加载的虚拟音频源 */
class WavFileAudioSource(private val wavFile: File) : VirtualAudioSource {
    private val pcmData: ShortArray
    private var position = 0

    init {
        pcmData = loadWavFile(wavFile)
    }

    override fun readFrame(): ShortArray? {
        if (position >= pcmData.size) return null
        val frameSize = minOf(FRAME_SIZE, pcmData.size - position)
        val frame = ShortArray(frameSize)
        System.arraycopy(pcmData, position, frame, 0, frameSize)
        position += frameSize
        return frame
    }

    override fun reset() {
        position = 0
    }

    override val totalFrames: Int
        get() = (pcmData.size + FRAME_SIZE - 1) / FRAME_SIZE

    override val description: String
        get() = "WAV file: ${wavFile.name}"

    private fun loadWavFile(file: File): ShortArray {
        val bytes = file.readBytes()
        // 跳过 WAV 头（44 字节）
        val dataOffset = 44
        val dataSize = bytes.size - dataOffset
        val samples = ShortArray(dataSize / 2)
        ByteBuffer.wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        return samples
    }

    companion object {
        const val FRAME_SIZE = 960  // 60ms @ 16kHz
    }
}

/** 真实音频源：从下载的真实语音文件加载 */
class RealWorldAudioSource(
    private val audioFile: File,
    private val subtitle: SubtitleEntry? = null
) : VirtualAudioSource {
    private val wavSource = WavFileAudioSource(audioFile)

    override fun readFrame(): ShortArray? = wavSource.readFrame()

    override fun reset() = wavSource.reset()

    override val totalFrames: Int
        get() = wavSource.totalFrames

    override val description: String
        get() = "Real-world audio: ${audioFile.name}" +
                (subtitle?.let { ", subtitle: ${it.text}" } ?: "")

    /** 获取关联的字幕（用于评分） */
    fun getSubtitle(): SubtitleEntry? = subtitle
}

/** 合成音频源：生成特定电平/频率的测试信号 */
class SyntheticAudioSource(
    private val pattern: AudioPattern,
    private val durationMs: Int = 1000,
    private val baseLevel: Float = 0.5f
) : VirtualAudioSource {
    private var position = 0
    private val totalSamples = (durationMs * SAMPLE_RATE / 1000)

    enum class AudioPattern {
        SILENCE,           // 静音
        CONSTANT_LEVEL,    // 恒定电平（测试 VAD 阈值）
        RAMP_UP,           // 电平渐增（测试触发灵敏度）
        RAMP_DOWN,         // 电平渐减（测试静音检测）
        PULSE,             // 脉冲（测试防抖）
        CHINESE_TONE,      // 中文声调模拟（测试句首轻声）
    }

    override fun readFrame(): ShortArray? {
        if (position >= totalSamples) return null
        val frameSize = minOf(FRAME_SIZE, totalSamples - position)
        val frame = ShortArray(frameSize)

        when (pattern) {
            AudioPattern.SILENCE -> {
                // 全零
            }
            AudioPattern.CONSTANT_LEVEL -> {
                val amplitude = (baseLevel * Short.MAX_VALUE).toInt().toShort()
                for (i in frame.indices) {
                    frame[i] = if (i % 2 == 0) amplitude else (-amplitude).toShort()
                }
            }
            AudioPattern.RAMP_UP -> {
                val progress = position.toFloat() / totalSamples
                val level = baseLevel * progress
                val amplitude = (level * Short.MAX_VALUE).toInt().toShort()
                for (i in frame.indices) {
                    frame[i] = if (i % 2 == 0) amplitude else (-amplitude).toShort()
                }
            }
            AudioPattern.RAMP_DOWN -> {
                val progress = position.toFloat() / totalSamples
                val level = baseLevel * (1 - progress)
                val amplitude = (level * Short.MAX_VALUE).toInt().toShort()
                for (i in frame.indices) {
                    frame[i] = if (i % 2 == 0) amplitude else (-amplitude).toShort()
                }
            }
            AudioPattern.PULSE -> {
                // 每 10 帧一个脉冲
                val pulseIndex = position / FRAME_SIZE
                val isPulse = pulseIndex % 10 == 0
                val amplitude = if (isPulse) (baseLevel * Short.MAX_VALUE).toInt().toShort() else 0
                for (i in frame.indices) {
                    frame[i] = if (i % 2 == 0) amplitude else (-amplitude).toShort()
                }
            }
            AudioPattern.CHINESE_TONE -> {
                // 模拟中文声调：先低后高（句首轻声）
                val frameIndex = position / FRAME_SIZE
                val level = if (frameIndex < 3) {
                    baseLevel * 0.3f  // 前 3 帧低电平（句首轻声）
                } else {
                    baseLevel
                }
                val amplitude = (level * Short.MAX_VALUE).toInt().toShort()
                for (i in frame.indices) {
                    frame[i] = if (i % 2 == 0) amplitude else (-amplitude).toShort()
                }
            }
        }

        position += frameSize
        return frame
    }

    override fun reset() {
        position = 0
    }

    override val totalFrames: Int
        get() = (totalSamples + FRAME_SIZE - 1) / FRAME_SIZE

    override val description: String
        get() = "Synthetic audio: pattern=$pattern, duration=${durationMs}ms, level=$baseLevel"

    companion object {
        const val FRAME_SIZE = 960
        const val SAMPLE_RATE = 16000
    }
}

/** 噪声叠加音频源：在干净语音上叠加可控噪声 */
class NoisyAudioSource(
    private val cleanSource: VirtualAudioSource,
    private val noiseLevel: Float,
    private val noiseType: NoiseType
) : VirtualAudioSource {
    private var position = 0

    enum class NoiseType { WHITE, PINK, BABBLE, STREET, AC_OUTDOOR }

    // ---- 空调外机噪声模型状态（跨帧保持，保证相位与滤波连续性） ----
    /** 全局采样计数（相位连续的确定性信号） */
    private var sampleCounter = 0L

    /** 一阶低通滤波器状态（模拟外机气流宽带噪声，截止约 300Hz） */
    private var lpState = 0.0

    /** 压缩机启停包络：1.0=运行 / 0.25=停机，启停切换带斜坡避免电平突变 */
    private var envelope = 1.0

    override fun readFrame(): ShortArray? {
        val cleanFrame = cleanSource.readFrame() ?: return null
        val noisyFrame = ShortArray(cleanFrame.size)

        for (i in cleanFrame.indices) {
            val noise = generateNoise(sampleCounter)
            sampleCounter++
            val noisySample = cleanFrame[i] + (noise * noiseLevel * Short.MAX_VALUE).toInt()
            noisyFrame[i] = noisySample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        position++
        return noisyFrame
    }

    override fun reset() {
        cleanSource.reset()
        position = 0
        sampleCounter = 0L
        lpState = 0.0
        envelope = 1.0
    }

    override val totalFrames: Int
        get() = cleanSource.totalFrames

    override val description: String
        get() = "Noisy audio: ${cleanSource.description}, noise=$noiseType@$noiseLevel"

    /**
     * 生成单个噪声样本。[index] 为全局采样序号，保证频率成分相位连续。
     */
    private fun generateNoise(index: Long): Float {
        return when (noiseType) {
            NoiseType.WHITE -> (Math.random() * 2 - 1).toFloat()
            NoiseType.PINK -> {
                // 简化的粉红噪声（低频偏重）
                val white = (Math.random() * 2 - 1).toFloat()
                white * (1.0f / (1 + (index % 10).toInt()))
            }
            NoiseType.BABBLE -> {
                // 模拟多人说话背景
                val freq1 = Math.sin(2 * Math.PI * 200 * index / SAMPLE_RATE)
                val freq2 = Math.sin(2 * Math.PI * 400 * index / SAMPLE_RATE)
                ((freq1 + freq2) / 2).toFloat()
            }
            NoiseType.STREET -> {
                // 模拟街道噪声（低频隆隆声）
                val rumble = Math.sin(2 * Math.PI * 50 * index / SAMPLE_RATE)
                val white = (Math.random() * 2 - 1).toFloat()
                (rumble * 0.7 + white * 0.3).toFloat()
            }
            NoiseType.AC_OUTDOOR -> {
                // 空调外机噪声建模（实体机常见背景干扰源，易导致“嗯/对”等幻觉词）：
                //  1) 风机/压缩机哼声：50Hz 基频 + 100Hz 谐波
                //  2) 气流宽带噪声：一阶低通白噪声（截止约 300Hz）
                //  3) 压缩机启停包络：约 8 秒周期，停机段衰减到 25%，
                //     切换带 320ms 斜坡（避免超出真实物理特征的电平突变）
                updateAcEnvelope(index)
                val hum = 0.5 * Math.sin(2 * Math.PI * 50 * index / SAMPLE_RATE) +
                    0.3 * Math.sin(2 * Math.PI * 100 * index / SAMPLE_RATE)
                val white = Math.random() * 2 - 1
                lpState += AC_LP_ALPHA * (white - lpState)
                (envelope * (0.55 * lpState + 0.45 * hum)).toFloat()
            }
        }
    }

    /** 更新压缩机启停包络（8 秒周期：5 秒运行 + 3 秒停机，切换带 0.32 秒斜坡） */
    private fun updateAcEnvelope(index: Long) {
        val cyclePos = (index % AC_COMPRESSOR_CYCLE_SAMPLES).toInt()
        val target = if (cyclePos < AC_COMPRESSOR_ON_SAMPLES) 1.0 else 0.25
        if (target > envelope) envelope = minOf(target, envelope + AC_ENVELOPE_STEP)
        else if (target < envelope) envelope = maxOf(target, envelope - AC_ENVELOPE_STEP)
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** 一阶低通系数：alpha = 1 - exp(-2π·300/16000) ≈ 0.11 */
        private const val AC_LP_ALPHA = 0.111

        /** 压缩机启停周期：8 秒 */
        private const val AC_COMPRESSOR_CYCLE_SAMPLES = SAMPLE_RATE * 8

        /** 周期内运行时长：5 秒 */
        private const val AC_COMPRESSOR_ON_SAMPLES = SAMPLE_RATE * 5

        /** 包络每采样最大变化量（320ms 内完成启停切换） */
        private const val AC_ENVELOPE_STEP = 0.75 / (SAMPLE_RATE * 0.32)
    }
}
