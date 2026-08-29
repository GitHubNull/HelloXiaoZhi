package org.oxff.helloxiaozhi.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxff.helloxiaozhi.chat.ChatState

/**
 * BottleneckAnalyzer 瓶颈分析器单元测试：
 * 用构造的报告数据验证各诊断规则的触发条件。
 */
class BottleneckAnalyzerTest {

    private fun baseReport(
        recall: Float = 1f,
        precision: Float = 1f,
        falsePositiveRate: Float = 0f,
        falseNegativeRate: Float = 0f,
        cer: Float = 0f,
        reference: String = "今天天气怎么样",
        hypothesis: String = "今天天气怎么样",
        levelPreservation: Float = 1f,
        snr: Float = 40f,
        e2eLatencyMs: Long = 200,
        vadTriggerDelayMs: Long = 180,
        scenarioName: String = "测试场景"
    ): AsrEvaluationReport = AsrEvaluationReport(
        scenarioName = scenarioName,
        timestamp = System.currentTimeMillis(),
        overallScore = 0f,
        cer = cer,
        wer = cer,
        sentenceAccuracy = reference == hypothesis,
        vadMetrics = VadAccuracyMetrics(precision, recall, 0f, falsePositiveRate, falseNegativeRate, 0f),
        endToEndLatencyMs = e2eLatencyMs,
        vadTriggerDelayMs = vadTriggerDelayMs,
        audioQuality = AudioQualityMetrics(snr, null, null, 0.01f, levelPreservation),
        stateMachineAccuracy = recall,
        unexpectedTransitions = emptyList(),
        referenceText = reference,
        hypothesisText = hypothesis,
        audioStats = AudioStats(0, 0, 0, emptyList(), emptyMap())
    )

    @Test
    fun `健康报告无瓶颈`() {
        assertTrue(BottleneckAnalyzer.analyze(baseReport()).isEmpty())
    }

    @Test
    fun `漏触发诊断为阈值过高`() {
        val report = baseReport(recall = 0.5f, falseNegativeRate = 0.5f)
        val bottlenecks = BottleneckAnalyzer.analyze(report)
        assertTrue(bottlenecks.any { it.category == BottleneckAnalyzer.Category.VAD_THRESHOLD_TOO_HIGH })
    }

    @Test
    fun `噪声场景误触发诊断为噪声敏感`() {
        val report = baseReport(
            precision = 0.6f, falsePositiveRate = 0.4f,
            scenarioName = "噪声_白噪声_测试"
        )
        val bottlenecks = BottleneckAnalyzer.analyze(report)
        assertTrue(bottlenecks.any { it.category == BottleneckAnalyzer.Category.NOISE_SENSITIVITY })
        assertTrue(bottlenecks.any { it.category == BottleneckAnalyzer.Category.VAD_THRESHOLD_TOO_LOW })
    }

    @Test
    fun `句首丢失诊断为预触发缓冲不足`() {
        // 「你猜」只识别到「猜」：句首字丢失
        val report = baseReport(
            cer = 0.5f, reference = "你猜", hypothesis = "猜"
        )
        val bottlenecks = BottleneckAnalyzer.analyze(report)
        val preRoll = bottlenecks.firstOrNull { it.category == BottleneckAnalyzer.Category.PRE_ROLL_INSUFFICIENT }
        assertTrue("应诊断出预触发缓冲不足", preRoll != null)
        assertEquals(BottleneckAnalyzer.Severity.CRITICAL, preRoll!!.severity)
    }

    @Test
    fun `高延迟诊断为网络瓶颈`() {
        val report = baseReport(e2eLatencyMs = 3500)
        val bottlenecks = BottleneckAnalyzer.analyze(report)
        val network = bottlenecks.firstOrNull { it.category == BottleneckAnalyzer.Category.NETWORK_LATENCY }
        assertTrue(network != null)
        assertEquals(BottleneckAnalyzer.Severity.CRITICAL, network!!.severity)
    }

    @Test
    fun `电平保持度低诊断为增益问题`() {
        val report = baseReport(levelPreservation = 0.5f)
        val bottlenecks = BottleneckAnalyzer.analyze(report)
        assertTrue(bottlenecks.any { it.category == BottleneckAnalyzer.Category.AUDIO_GAIN_ISSUE })
    }

    @Test
    fun `批量分析聚合受影响场景`() {
        val reports = listOf(
            baseReport(recall = 0.5f, falseNegativeRate = 0.5f, scenarioName = "场景A"),
            baseReport(recall = 0.6f, falseNegativeRate = 0.4f, scenarioName = "场景B")
        )
        val bottlenecks = BottleneckAnalyzer.analyzeAll(reports)
        val thresholdHigh = bottlenecks.first { it.category == BottleneckAnalyzer.Category.VAD_THRESHOLD_TOO_HIGH }
        assertTrue(thresholdHigh.affectedScenarios.containsAll(listOf("场景A", "场景B")))
        assertTrue(thresholdHigh.recommendation.contains("THRESHOLD_SPEAKING"))
    }
}
