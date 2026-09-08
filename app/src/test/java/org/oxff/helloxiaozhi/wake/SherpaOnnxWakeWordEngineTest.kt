package org.oxff.helloxiaozhi.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * SherpaOnnxWakeWordEngine 单元测试。
 *
 * 注意：构建引擎需要真实 KWS 模型（assets）与 sherpa-onnx 原生库，
 * 这些测试在 JVM/Robolectric 环境下无法真正初始化引擎，
 * 因此仅验证构造与未初始化状态的边界行为。
 */
@RunWith(RobolectricTestRunner::class)
class SherpaOnnxWakeWordEngineTest {

    private lateinit var engine: SherpaOnnxWakeWordEngine

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        engine = SherpaOnnxWakeWordEngine(
            context = context,
            keywords = "小智小智",
            sensitivity = 0.5f,
        )
    }

    @Test
    fun `初始状态未初始化`() {
        assertFalse(engine.isInitialized)
    }

    @Test
    fun `采样率为 16000`() {
        assertEquals(16000, engine.sampleRate)
    }

    @Test
    fun `未初始化时 acceptAudio 不崩溃`() {
        engine.acceptAudio(FloatArray(1600) { it / 32768.0f })
        // 未初始化时直接返回，不抛异常
        assertFalse(engine.isInitialized)
    }

    @Test
    fun `release 后状态为未初始化`() {
        engine.release()
        assertFalse(engine.isInitialized)
    }

    @Test
    fun `默认唤醒词为阿妹阿妹`() {
        // 与 SherpaOnnxWakeWordEngine.DEFAULT_KEYWORD 同步（此前断言仍为旧值 "小智小智" 导致失败）
        assertEquals("阿妹阿妹", SherpaOnnxWakeWordEngine.DEFAULT_KEYWORD)
        assertTrue(SherpaOnnxWakeWordEngine.DEFAULT_KEYWORD.isNotBlank())
    }
}
