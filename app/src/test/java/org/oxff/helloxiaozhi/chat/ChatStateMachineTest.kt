package org.oxff.helloxiaozhi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxff.helloxiaozhi.util.DirectExecutor
import org.oxff.helloxiaozhi.util.SilenceScheduler

/**
 * ChatStateMachine 状态迁移单元测试：
 * 阈值触发、静音计时、打断、listen/abort 消息副作用。
 * 使用 DirectExecutor + 可控 FakeSilenceScheduler，无需 Android 运行时。
 */
class ChatStateMachineTest {

    /** 记录状态机发出的所有文本消息与音频帧 */
    private class RecordingCallbacks : ChatStateMachine.Callbacks {
        val textMessages = mutableListOf<Any>()
        val audioFrames = mutableListOf<ShortArray>()
        val events = mutableListOf<ChatEvent>()

        override fun sendAudioData(frame: ShortArray) {
            audioFrames.add(frame)
        }

        override fun sendTextData(message: Any) {
            textMessages.add(message)
        }

        override fun getSessionId(): String = SESSION_ID

        override fun onEvent(event: ChatEvent) {
            events.add(event)
        }
    }

    /** 假静音调度器：记录任务，测试手动触发 */
    private class FakeSilenceScheduler : SilenceScheduler {
        var scheduled: (() -> Unit)? = null
            private set
        var scheduleCount = 0
            private set
        var cancelCount = 0
            private set

        override fun schedule(delayMs: Long, action: () -> Unit) {
            scheduled = action
            scheduleCount++
        }

        override fun cancel() {
            scheduled = null
            cancelCount++
        }

        fun trigger() {
            val action = scheduled ?: return
            scheduled = null
            action()
        }
    }

    private lateinit var scheduler: FakeSilenceScheduler
    private lateinit var callbacks: RecordingCallbacks
    private lateinit var machine: ChatStateMachine

    private fun createMachine() {
        scheduler = FakeSilenceScheduler()
        callbacks = RecordingCallbacks()
        machine = ChatStateMachine(DirectExecutor(), scheduler, callbacks)
    }

    private fun frame(level: Float) = ShortArray(960)

    // ---------------- 状态迁移 ----------------

    @Test
    fun `IDLE 电平超阈值进入 USER_SPEAKING 并发送 listen start`() {
        createMachine()
        machine.handleAudioLevel(0.05f, frame(0.05f))
        assertEquals(ChatState.USER_SPEAKING, machine.state)
        assertEquals(1, callbacks.textMessages.size)
        assertTrue(callbacks.textMessages[0] is ListenMessage)
        assertEquals("start", (callbacks.textMessages[0] as ListenMessage).state)
        assertTrue(callbacks.events.contains(ChatEvent.USER_START_SPEAKING))
    }

    @Test
    fun `IDLE 电平低于阈值保持 IDLE 不发送消息`() {
        createMachine()
        machine.handleAudioLevel(0.03f, frame(0.03f))
        assertEquals(ChatState.IDLE, machine.state)
        assertTrue(callbacks.textMessages.isEmpty())
    }

    @Test
    fun `IDLE 电平恰好等于阈值不触发`() {
        createMachine()
        machine.handleAudioLevel(ChatStateMachine.THRESHOLD_SPEAKING, frame(0.04f))
        assertEquals(ChatState.IDLE, machine.state)
    }

    // ---------------- USER_SPEAKING 音频帧与静音计时 ----------------

    @Test
    fun `USER_SPEAKING 期间持续发送音频帧`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        machine.handleAudioLevel(0.3f, frame(0.3f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        assertEquals(2, callbacks.audioFrames.size)
        assertEquals(ChatState.USER_SPEAKING, machine.state)
    }

    @Test
    fun `静音启动计时 到期后进入 AI_SPEAKING 并发送 listen stop`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        machine.handleAudioLevel(0.01f, frame(0.01f))
        assertEquals(1, scheduler.scheduleCount)
        scheduler.trigger()
        assertEquals(ChatState.AI_SPEAKING, machine.state)
        val stop = callbacks.textMessages.last() as ListenMessage
        assertEquals("stop", stop.state)
        assertTrue(callbacks.events.contains(ChatEvent.USER_STOP_SPEAKING))
        assertTrue(callbacks.events.contains(ChatEvent.AI_START_SPEAKING))
    }

    @Test
    fun `恢复说话取消静音计时 保持 USER_SPEAKING`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        machine.handleAudioLevel(0.01f, frame(0.01f)) // 静音 → 启动计时
        assertEquals(1, scheduler.scheduleCount)
        machine.handleAudioLevel(0.3f, frame(0.3f))   // 说话 → 取消计时
        assertTrue(scheduler.cancelCount >= 1)
        assertNull(scheduler.scheduled)
        scheduler.trigger() // 假任务已取消，不应有任何效果
        assertEquals(ChatState.USER_SPEAKING, machine.state)
    }

    @Test
    fun `静音期间重复静音帧不重复启动计时`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        machine.handleAudioLevel(0.01f, frame(0.01f))
        machine.handleAudioLevel(0.01f, frame(0.01f))
        assertEquals(1, scheduler.scheduleCount)
    }

    // ---------------- AI_SPEAKING 打断 ----------------

    @Test
    fun `AI_SPEAKING 电平超打断阈值发送 abort 转 USER_SPEAKING`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        machine.handleAudioLevel(0.2f, frame(0.2f))
        assertEquals(ChatState.USER_SPEAKING, machine.state)
        // 打断时先发 abort，随后进入 USER_SPEAKING 再发 listen start
        val abort = callbacks.textMessages.filterIsInstance<AbortMessage>().last()
        assertEquals(SESSION_ID, abort.sessionId)
    }

    @Test
    fun `AI_SPEAKING 低电平保持状态`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        machine.handleAudioLevel(0.05f, frame(0.05f))
        assertEquals(ChatState.AI_SPEAKING, machine.state)
        assertTrue(callbacks.textMessages.none { it is AbortMessage })
    }

    // ---------------- 显式状态切换 ----------------

    @Test
    fun `退出 USER_SPEAKING 发送 listen stop 并取消计时`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        machine.handleAudioLevel(0.01f, frame(0.01f)) // 挂起计时
        machine.setState(ChatState.IDLE)
        val stop = callbacks.textMessages.last() as ListenMessage
        assertEquals("stop", stop.state)
        assertTrue(scheduler.cancelCount >= 1)
        assertTrue(callbacks.events.contains(ChatEvent.USER_STOP_SPEAKING))
    }

    @Test
    fun `重复 setState 相同状态无副作用`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        val messageCount = callbacks.textMessages.size
        machine.setState(ChatState.AI_SPEAKING)
        assertEquals(messageCount, callbacks.textMessages.size)
    }

    private companion object {
        const val SESSION_ID = "test-session"
    }
}
