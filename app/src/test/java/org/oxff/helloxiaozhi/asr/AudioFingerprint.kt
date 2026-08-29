package org.oxff.helloxiaozhi.asr

/**
 * 音频指纹：用于模拟服务器匹配特定音频片段，决定返回哪条预设的 STT 结果。
 *
 * 由三部分组成：
 *  - frameCount：60ms 帧数量（时长特征）
 *  - averageLevel：平均 RMS 电平（能量特征）
 *  - spectralHash：简化频谱特征哈希（过零率 + 能量包络分桶）
 *
 * 测试环境中无需真正的音频检索：指纹只需在同一测试内可重复计算即可。
 */
data class AudioFingerprint(
    val frameCount: Int,
    val averageLevel: Float,
    val spectralHash: Int
) {
    companion object {
        const val FRAME_SIZE = 960  // 60ms @ 16kHz

        /** 从一个完整片段的 PCM 采样计算指纹 */
        fun fromPcm(pcm: ShortArray): AudioFingerprint {
            val frameCount = (pcm.size + FRAME_SIZE - 1) / FRAME_SIZE
            if (frameCount == 0 || pcm.isEmpty()) {
                return AudioFingerprint(0, 0f, 0)
            }

            // 平均电平（RMS，与 AudioMath.rmsLevel 同口径：绝对均值近似）
            var sumAbs = 0.0
            var zeroCrossings = 0
            var prev = 0
            for (i in pcm.indices) {
                val v = pcm[i].toInt()
                sumAbs += Math.abs(v)
                if (i > 0 && (v >= 0) != (prev >= 0)) zeroCrossings++
                prev = v
            }
            val averageLevel = (sumAbs / pcm.size / Short.MAX_VALUE).toFloat()

            // 简化频谱哈希：过零次数（频率特征，直接计入哈希）× 每帧能量包络哈希
            val zcrFeature = zeroCrossings
            var envelopeHash = 17
            var frameStart = 0
            while (frameStart < pcm.size) {
                val end = minOf(frameStart + FRAME_SIZE, pcm.size)
                var frameMax = 0
                for (i in frameStart until end) {
                    val a = Math.abs(pcm[i].toInt())
                    if (a > frameMax) frameMax = a
                }
                envelopeHash = envelopeHash * 31 + (frameMax / 4096)
                frameStart = end
            }
            val spectralHash = zcrFeature * 1_000_003 + envelopeHash

            return AudioFingerprint(frameCount, averageLevel, spectralHash)
        }

        /** 从帧列表计算指纹 */
        fun fromFrames(frames: List<ShortArray>): AudioFingerprint {
            val total = frames.sumOf { it.size }
            val pcm = ShortArray(total)
            var offset = 0
            for (frame in frames) {
                System.arraycopy(frame, 0, pcm, offset, frame.size)
                offset += frame.size
            }
            return fromPcm(pcm)
        }
    }

    /**
     * 容差匹配：真实链路存在重采样/编码损耗，指纹允许帧数 ±2、电平 ±0.05 的偏差。
     * spectralHash 用于强校验场景（完全相同的合成音频）。
     */
    fun matches(other: AudioFingerprint, frameTolerance: Int = 2, levelTolerance: Float = 0.05f): Boolean {
        if (Math.abs(frameCount - other.frameCount) > frameTolerance) return false
        if (Math.abs(averageLevel - other.averageLevel) > levelTolerance) return false
        return true
    }
}
