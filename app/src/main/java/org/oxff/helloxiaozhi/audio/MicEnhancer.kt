package org.oxff.helloxiaozhi.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * 上行麦克风帧级语音增强器：AGC（自动增益）+ 噪声门。
 *
 * 背景：语音触发依赖服务器端 VAD 对上行 Opus 帧能量的判断，轻声/悄悄话
 * 电平过低无法触发；但若用固定增益整体抬高，底噪也会被等比放大导致噪声
 * 误触发。本类按帧自适应处理，同时解决两个问题：
 *  - AGC：轻声帧被大幅放大（上限 +24dB）推向 [targetLevel]，正常/大声
 *    说话帧接近目标电平几乎不放大、不削波，轻声与大声说话的上行电平被拉平；
 *  - 噪声门：跟踪底噪，低于「底噪 × [gateMargin]」的帧被衰减到 [gateGain]，
 *    防止 AGC 把背景噪声放大成"假语音"。
 *
 * 线程模型：仅由录音线程单线程调用，无需加锁。纯 Kotlin 无 Android 依赖，
 * 便于 JVM 单元测试。
 *
 * 注意：本类只作用于**上行**帧；送给状态机的电平仍用原始帧 + 固定 +12dB
 * 基准（见 AudioRecorderManager.VAD_REFERENCE_GAIN_DB），与用户增益解耦的
 * 历史约定不受影响。
 */
class MicEnhancer(
    /** 目标峰值电平（0..1），轻声被放大逼近该值；留削波余量供服务器 ASR */
    private val targetLevel: Float = DEFAULT_TARGET_PEAK,
    /** 最大增益（线性系数），轻声放大上限，默认 +24dB ≈ 16 倍 */
    private val maxGain: Float = dbToFactor(DEFAULT_MAX_GAIN_DB),
    /** 最小增益（线性系数），大声时的衰减下限，默认 -12dB 防削波 */
    private val minGain: Float = dbToFactor(DEFAULT_MIN_GAIN_DB),
    /** 噪声门阈值 = noiseFloor × gateMargin，高于阈值的帧不衰减 */
    private val gateMargin: Float = DEFAULT_GATE_MARGIN,
    /** 噪声门完全关闭时的衰减系数 */
    private val gateGain: Float = DEFAULT_GATE_GAIN,
) {

    /** 估计的语音峰值电平（快攻击 / 慢释放） */
    private var estPeak = 0f

    /** 底噪电平估计（慢升快降） */
    private var noiseFloor = 0f

    /** 当前 AGC 增益（帧间平滑，防泵浦） */
    private var gain = 1f

    /** 诊断用：最近一帧处理后的增益（含门控系数） */
    var lastGain: Float = 1f
        private set

    /** 诊断用：当前底噪估计 */
    val currentNoiseFloor: Float get() = noiseFloor

    /** 诊断用：当前语音电平估计 */
    val currentEstPeak: Float get() = estPeak

    /**
     * 处理一帧上行 PCM，返回增强后的新帧（不修改入参）。
     *
     * 帧内所有采样使用同一增益（60ms 粒度），帧间增益平滑过渡，
     * Opus 按帧独立编码，帧边界增益跳变不会产生爆音。
     */
    fun process(frame: ShortArray): ShortArray {
        val peak = framePeak(frame)
        // 引导：用第一帧初始化底噪，保证通话开始后的持续噪声尽快被门控
        if (noiseFloor == 0f && peak > 0f) noiseFloor = peak
        val isSpeech = peak > noiseFloor * SPEECH_OVER_NOISE
        updateNoiseFloor(peak)
        updateSpeechEstimate(peak, isSpeech)
        updateGain()
        val gate = gateMultiplier(peak)
        val total = gain * gate
        lastGain = total
        return ShortArray(frame.size) { i ->
            (frame[i] * total).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** 重置全部状态（每次开始通话时调用） */
    fun reset() {
        estPeak = 0f
        noiseFloor = 0f
        gain = 1f
        lastGain = 1f
    }

    /** 帧峰值电平（0..1） */
    private fun framePeak(frame: ShortArray): Float {
        var peak = 0
        for (s in frame) peak = max(peak, abs(s.toInt()))
        return peak / 32768f
    }

    /**
     * 底噪跟踪：只有低于起始阈值（底噪 × [FLOOR_TRACK_RATIO]）的帧参与
     * 跟踪，防止语音抬高底噪（持续轻声也不会被误学成底噪）；
     * 跟踪本身慢升快降：帧峰值低于底噪 → 快速下探，略高 → 缓慢上爬，
     * 使持续的环境噪声在约 1 秒内收敛到底噪估计。
     */
    private fun updateNoiseFloor(peak: Float) {
        if (peak >= noiseFloor * FLOOR_TRACK_RATIO) return
        if (peak < noiseFloor) {
            noiseFloor = NOISE_FALL_ALPHA * noiseFloor + (1 - NOISE_FALL_ALPHA) * peak
        } else {
            noiseFloor += NOISE_RISE_ALPHA * (peak - noiseFloor)
        }
    }

    /**
     * 语音电平估计：快攻击（0.3 混合新峰值）/ 慢释放（每帧 ×0.995，
     * 约 8 秒半衰），保证句间停顿后增益不会立刻冲顶放大底噪。
     */
    private fun updateSpeechEstimate(peak: Float, isSpeech: Boolean) {
        estPeak = if (isSpeech) {
            if (estPeak == 0f) peak else EST_ATTACK_ALPHA * estPeak + (1 - EST_ATTACK_ALPHA) * peak
        } else {
            estPeak * EST_RELEASE_FACTOR
        }
    }

    /**
     * AGC 增益更新：期望增益 = targetLevel / estPeak，截断到
     * [minGain, maxGain]；增益平滑使用非对称系数——上升（放大轻声）
     * 用攻击系数快速跟上，下降（防大声削波）用释放系数平滑回落。
     */
    private fun updateGain() {
        if (estPeak <= 0f) return
        val desired = (targetLevel / estPeak).coerceIn(minGain, maxGain)
        val alpha = if (desired > gain) ATTACK_ALPHA else RELEASE_ALPHA
        gain += alpha * (desired - gain)
    }

    /**
     * 噪声门系数：
     *  - peak >= noiseFloor × gateMargin → 1（不衰减）
     *  - peak <= noiseFloor → [gateGain]（完全关闭）
     *  - 中间区间线性过渡，避免门控咔哒声
     * 底噪尚未建立（=0）时不启用门控。
     */
    private fun gateMultiplier(peak: Float): Float {
        if (noiseFloor <= 0f) return 1f
        val openThreshold = noiseFloor * gateMargin
        if (peak >= openThreshold) return 1f
        if (peak <= noiseFloor) return gateGain
        val t = (peak - noiseFloor) / (openThreshold - noiseFloor)
        return gateGain + (1f - gateGain) * t
    }

    companion object {
        /** 目标峰值电平，约 -10dBFS，留足削波余量 */
        const val DEFAULT_TARGET_PEAK = 0.3f

        /** 轻声放大上限 +24dB：实测需 45%~55% 音量，轻声约为其 1/8~1/16 */
        const val DEFAULT_MAX_GAIN_DB = 24f

        /** 大声衰减下限 -12dB，防削波 */
        const val DEFAULT_MIN_GAIN_DB = -12f

        /** 噪声门开启阈值 = 底噪 × 2 */
        const val DEFAULT_GATE_MARGIN = 2.0f

        /** 门下衰减到 0.1 倍 */
        const val DEFAULT_GATE_GAIN = 0.1f

        /** 语音判定：峰值超过底噪 3 倍视为语音帧 */
        private const val SPEECH_OVER_NOISE = 3f

        /** 底噪跟踪阈值：峰值超过底噪 4 倍视为起始（语音/突发），不参与底噪跟踪 */
        private const val FLOOR_TRACK_RATIO = 4f

        /** 语音电平估计的攻击混合系数（越大越保留历史） */
        private const val EST_ATTACK_ALPHA = 0.7f

        /** 语音电平估计的每帧释放衰减（≈8 秒半衰） */
        private const val EST_RELEASE_FACTOR = 0.995f

        /** 增益攻击平滑系数（上升快，轻声句首尽快跟上） */
        private const val ATTACK_ALPHA = 0.5f

        /** 增益释放平滑系数（下降慢，防泵浦） */
        private const val RELEASE_ALPHA = 0.15f

        /** 底噪快速下探系数 */
        private const val NOISE_FALL_ALPHA = 0.7f

        /** 底噪上爬系数（持续环境噪声约 1 秒收敛） */
        private const val NOISE_RISE_ALPHA = 0.1f

        /** dB 转线性系数 */
        fun dbToFactor(db: Float): Float = 10.0.pow((db / 20.0).toDouble()).toFloat()
    }
}
