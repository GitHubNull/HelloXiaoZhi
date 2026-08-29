package org.oxff.helloxiaozhi.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxff.helloxiaozhi.chat.ChatState

/**
 * MetricsEngine 指标计算引擎单元测试：
 * CER / WER / 句子准确率 / VAD 准确率 / 延迟 / 音频质量。
 */
class MetricsEngineTest {

    // ---------------- CER / WER ----------------

    @Test
    fun `CER 完全相同为 0`() {
        assertEquals(0f, MetricsEngine.calculateCER("今天天气怎么样", "今天天气怎么样"), 1e-6f)
    }

    @Test
    fun `CER 单字替换`() {
        // 参考 7 字，替换 1 字 → 1/7
        assertEquals(1f / 7, MetricsEngine.calculateCER("今天天气怎么样", "今天天气怎么杨"), 1e-6f)
    }

    @Test
    fun `CER 句首丢失 对应预触发缓冲不足`() {
        // 「你猜」只识别到「猜」→ 删除 1 字 → 1/2
        assertEquals(0.5f, MetricsEngine.calculateCER("你猜", "猜"), 1e-6f)
    }

    @Test
    fun `CER 空参考与非空假设`() {
        assertEquals(1f, MetricsEngine.calculateCER("", "多余输出"), 1e-6f)
    }

    @Test
    fun `CER 双空为 0`() {
        assertEquals(0f, MetricsEngine.calculateCER("", ""), 1e-6f)
    }

    @Test
    fun `WER 按空白分词计算`() {
        assertEquals(0f, MetricsEngine.calculateWER("hello world", "hello world"), 1e-6f)
        assertEquals(0.5f, MetricsEngine.calculateWER("hello world", "hello there"), 1e-6f)
    }

    // ---------------- 句子准确率 ----------------

    @Test
    fun `句子准确率 忽略标点与空白`() {
        assertTrue(MetricsEngine.calculateSentenceAccuracy("今天，天气怎么样？", "今天天气怎么样"))
        assertFalse(MetricsEngine.calculateSentenceAccuracy("今天天气怎么样", "明天天气怎么样"))
    }

    // ---------------- VAD 准确率 ----------------

    private fun transition(from: ChatState, to: ChatState) =
        StateTransition(from, to, Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 0)

    @Test
    fun `VAD 完全匹配为满分`() {
        val expected = listOf(
            transition(ChatState.IDLE, ChatState.USER_SPEAKING),
            transition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING)
        )
        val metrics = MetricsEngine.calculateVadAccuracy(expected, expected.toList())
        assertEquals(1f, metrics.precision, 1e-6f)
        assertEquals(1f, metrics.recall, 1e-6f)
        assertEquals(0f, metrics.falsePositiveRate, 1e-6f)
        assertEquals(0f, metrics.falseNegativeRate, 1e-6f)
    }

    @Test
    fun `VAD 双方无迁移视为完美 不应触发场景`() {
        val metrics = MetricsEngine.calculateVadAccuracy(emptyList(), emptyList())
        assertEquals(1f, metrics.precision, 1e-6f)
        assertEquals(1f, metrics.recall, 1e-6f)
    }

    @Test
    fun `VAD 漏触发 召回率下降`() {
        val expected = listOf(transition(ChatState.IDLE, ChatState.USER_SPEAKING))
        val metrics = MetricsEngine.calculateVadAccuracy(expected, emptyList())
        assertEquals(0f, metrics.recall, 1e-6f)
        assertEquals(1f, metrics.falseNegativeRate, 1e-6f)
    }

    @Test
    fun `VAD 误触发 精确率下降`() {
        val actual = listOf(transition(ChatState.IDLE, ChatState.USER_SPEAKING))
        val metrics = MetricsEngine.calculateVadAccuracy(emptyList(), actual)
        assertEquals(0f, metrics.precision, 1e-6f)
        assertEquals(1f, metrics.falsePositiveRate, 1e-6f)
    }

    // ---------------- 延迟与音频质量 ----------------

    @Test
    fun `端到端延迟计算`() {
        assertEquals(1200L, MetricsEngine.calculateLatency(10_000L, 11_200L))
    }

    @Test
    fun `音频质量 相同信号电平保持度为 1`() {
        val pcm = ShortArray(960) { (it % 1000).toShort() }
        val quality = MetricsEngine.calculateAudioQuality(pcm, pcm.copyOf())
        assertEquals(1f, quality.levelPreservation, 1e-3f)
        assertEquals(0f, quality.spectralDistortion, 1e-6f)
    }

    @Test
    fun `音频质量 长度不一致判定为失真`() {
        val original = ShortArray(1920) { 100 }
        val truncated = ShortArray(960) { 100 }  // 模拟句首/句尾丢失
        val quality = MetricsEngine.calculateAudioQuality(original, truncated)
        assertEquals(0f, quality.levelPreservation, 1e-6f)
    }
}
