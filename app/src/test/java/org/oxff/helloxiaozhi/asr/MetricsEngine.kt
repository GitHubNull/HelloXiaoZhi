package org.oxff.helloxiaozhi.asr

/**
 * ASR 评估指标计算引擎。
 */
object MetricsEngine {

    /** 计算词错误率 (Word Error Rate) */
    fun calculateWER(reference: String, hypothesis: String): Float {
        val refWords = reference.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hypWords = hypothesis.split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (refWords.isEmpty()) return if (hypWords.isEmpty()) 0f else 1f

        val distance = levenshteinDistance(refWords, hypWords)
        return distance.toFloat() / refWords.size
    }

    /** 计算字错误率 (Character Error Rate) —— 中文场景更适用 */
    fun calculateCER(reference: String, hypothesis: String): Float {
        val refChars = reference.toList()
        val hypChars = hypothesis.toList()

        if (refChars.isEmpty()) return if (hypChars.isEmpty()) 0f else 1f

        val distance = levenshteinDistance(refChars, hypChars)
        return distance.toFloat() / refChars.size
    }

    /** 计算句子级准确率 */
    fun calculateSentenceAccuracy(reference: String, hypothesis: String): Boolean {
        // 去除标点符号和空白后比较（\p{P} 覆盖全角中文标点，\p{Punct} 仅 ASCII）
        val refClean = reference.replace(Regex("[\\p{P}\\p{S}\\s]"), "")
        val hypClean = hypothesis.replace(Regex("[\\p{P}\\p{S}\\s]"), "")
        return refClean == hypClean
    }

    /** 计算 VAD 触发准确率 */
    fun calculateVadAccuracy(
        expectedTransitions: List<StateTransition>,
        actualTransitions: List<StateTransition>
    ): VadAccuracyMetrics {
        // 双方都无迁移（如「不应触发」场景正确保持 IDLE）→ 完美结果
        if (expectedTransitions.isEmpty() && actualTransitions.isEmpty()) {
            return VadAccuracyMetrics(1f, 1f, 1f, 0f, 0f, 0f)
        }

        var truePositives = 0
        var falsePositives = 0
        var falseNegatives = 0
        var totalDelay = 0L

        // 匹配期望和实际的转换
        val matched = mutableSetOf<Int>()
        for (expected in expectedTransitions) {
            var found = false
            for ((index, actual) in actualTransitions.withIndex()) {
                if (index in matched) continue
                if (expected.from == actual.from && expected.to == actual.to) {
                    truePositives++
                    totalDelay += actual.maxDelayMs
                    matched.add(index)
                    found = true
                    break
                }
            }
            if (!found) {
                falseNegatives++
            }
        }

        // 未匹配的实际转换为误触发
        falsePositives = actualTransitions.size - matched.size

        val precision = if (truePositives + falsePositives > 0) {
            truePositives.toFloat() / (truePositives + falsePositives)
        } else 0f

        val recall = if (truePositives + falseNegatives > 0) {
            truePositives.toFloat() / (truePositives + falseNegatives)
        } else 0f

        val f1Score = if (precision + recall > 0) {
            2 * precision * recall / (precision + recall)
        } else 0f

        val falsePositiveRate = if (actualTransitions.isNotEmpty()) {
            falsePositives.toFloat() / actualTransitions.size
        } else 0f

        val falseNegativeRate = if (expectedTransitions.isNotEmpty()) {
            falseNegatives.toFloat() / expectedTransitions.size
        } else 0f

        val averageDelayMs = if (truePositives > 0) {
            totalDelay.toFloat() / truePositives
        } else 0f

        return VadAccuracyMetrics(
            precision = precision,
            recall = recall,
            f1Score = f1Score,
            falsePositiveRate = falsePositiveRate,
            falseNegativeRate = falseNegativeRate,
            averageDelayMs = averageDelayMs
        )
    }

    /** 计算端到端延迟 */
    fun calculateLatency(
        audioEndTime: Long,
        sttReceivedTime: Long
    ): Long {
        return sttReceivedTime - audioEndTime
    }

    /** 计算音频质量指标 */
    fun calculateAudioQuality(
        originalPcm: ShortArray,
        processedPcm: ShortArray
    ): AudioQualityMetrics {
        // 计算信噪比 (SNR)
        val snr = calculateSNR(originalPcm, processedPcm)

        // 计算电平保持度
        val levelPreservation = calculateLevelPreservation(originalPcm, processedPcm)

        // 计算频谱失真度（简化版）
        val spectralDistortion = calculateSpectralDistortion(originalPcm, processedPcm)

        return AudioQualityMetrics(
            snr = snr,
            pesq = null,  // 需要额外库
            stoi = null,  // 需要额外库
            spectralDistortion = spectralDistortion,
            levelPreservation = levelPreservation
        )
    }

    /** Levenshtein 距离（编辑距离） */
    private fun <T> levenshteinDistance(s1: List<T>, s2: List<T>): Int {
        val m = s1.size
        val n = s2.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // 删除
                    dp[i][j - 1] + 1,      // 插入
                    dp[i - 1][j - 1] + cost // 替换
                )
            }
        }

        return dp[m][n]
    }

    /** 计算信噪比 */
    private fun calculateSNR(original: ShortArray, processed: ShortArray): Float {
        if (original.size != processed.size) return 0f

        var signalPower = 0.0
        var noisePower = 0.0

        for (i in original.indices) {
            val signal = original[i].toDouble()
            val noise = (processed[i] - original[i]).toDouble()
            signalPower += signal * signal
            noisePower += noise * noise
        }

        if (noisePower == 0.0) return Float.MAX_VALUE
        // 无信号（如纯静音片段）时无可比的信噪比，返回 0 避免 0/0 产生 NaN 污染报告
        if (signalPower == 0.0) return 0f
        return (10 * Math.log10(signalPower / noisePower)).toFloat()
    }

    /** 计算电平保持度 */
    private fun calculateLevelPreservation(original: ShortArray, processed: ShortArray): Float {
        if (original.size != processed.size) return 0f

        var originalLevel = 0.0
        var processedLevel = 0.0

        for (i in original.indices) {
            originalLevel += Math.abs(original[i].toInt())
            processedLevel += Math.abs(processed[i].toInt())
        }

        if (originalLevel == 0.0) return 1f
        return (processedLevel / originalLevel).toFloat().coerceIn(0f, 1f)
    }

    /** 计算频谱失真度（简化版） */
    private fun calculateSpectralDistortion(original: ShortArray, processed: ShortArray): Float {
        if (original.size != processed.size) return 1f
        if (original.isEmpty()) return 0f  // 双方均为空（纯静音场景）视为无失真

        // 简化版：计算样本差异的平均值
        var totalDiff = 0.0
        for (i in original.indices) {
            totalDiff += Math.abs(original[i] - processed[i])
        }

        val avgDiff = totalDiff / original.size
        val maxPossibleDiff = Short.MAX_VALUE.toDouble()

        return (avgDiff / maxPossibleDiff).toFloat().coerceIn(0f, 1f)
    }
}

data class VadAccuracyMetrics(
    val precision: Float,      // 精确率：正确触发 / 总触发
    val recall: Float,         // 召回率：正确触发 / 应触发
    val f1Score: Float,        // F1 分数
    val falsePositiveRate: Float,  // 误触发率
    val falseNegativeRate: Float,  // 漏触发率
    val averageDelayMs: Float      // 平均触发延迟
)

data class AudioQualityMetrics(
    val snr: Float,            // 信噪比
    val pesq: Float?,          // PESQ 分数（可选，需额外库）
    val stoi: Float?,          // STOI 分数（可选）
    val spectralDistortion: Float,  // 频谱失真度
    val levelPreservation: Float    // 电平保持度
)

/** 综合评分报告 */
data class AsrEvaluationReport(
    val scenarioName: String,
    val timestamp: Long,
    val overallScore: Float,  // 0..100 综合评分

    // 识别准确率
    val cer: Float,
    val wer: Float,
    val sentenceAccuracy: Boolean,

    // VAD 性能
    val vadMetrics: VadAccuracyMetrics,

    // 延迟指标
    val endToEndLatencyMs: Long,
    val vadTriggerDelayMs: Long,

    // 音频质量
    val audioQuality: AudioQualityMetrics,

    // 状态机行为
    val stateMachineAccuracy: Float,
    val unexpectedTransitions: List<StateTransition>,

    // 原始数据
    val referenceText: String,
    val hypothesisText: String,
    val audioStats: AudioStats
)

/** 音频统计信息 */
data class AudioStats(
    val totalFrames: Int,
    val totalBytes: Long,
    val averageFrameSize: Int,
    val frameIntervalMs: List<Long>,  // 帧间隔分布
    val levelDistribution: Map<String, Int>  // 电平分布直方图
)
