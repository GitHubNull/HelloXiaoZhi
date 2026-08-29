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
        // 需要连续 4 帧超过阈值才触发（防抖机制）
        machine.handleAudioLevel(0.05f, frame(0.05f))
        assertEquals(ChatState.IDLE, machine.state) // 第 1 帧，未触发
        machine.handleAudioLevel(0.05f, frame(0.05f))
        assertEquals(ChatState.IDLE, machine.state) // 第 2 帧，未触发
        machine.handleAudioLevel(0.05f, frame(0.05f))
        assertEquals(ChatState.IDLE, machine.state) // 第 3 帧，未触发
        machine.handleAudioLevel(0.05f, frame(0.05f))
        assertEquals(ChatState.USER_SPEAKING, machine.state) // 第 4 帧，触发
        // 触发时先补发预触发缓冲帧（4 帧），再发 listen start
        assertEquals(4, callbacks.audioFrames.size)
        assertEquals(1, callbacks.textMessages.size)
        val listen = callbacks.textMessages[0] as ListenMessage
        assertEquals("start", listen.state)
        assertEquals(SESSION_ID, listen.sessionId)
        assertTrue(callbacks.events.contains(ChatEvent.USER_START_SPEAKING))
    }

    @Test
    fun `IDLE 期间音频帧进入预触发缓冲 触发时按序补发`() {
        createMachine()
        // 先送入 2 帧低电平（不触发），再送入 4 帧高电平触发
        machine.handleAudioLevel(0.01f, frame(0.01f))
        machine.handleAudioLevel(0.01f, frame(0.01f))
        assertTrue(callbacks.audioFrames.isEmpty()) // IDLE 不直接发送
        repeat(4) { machine.handleAudioLevel(0.05f, frame(0.05f)) }
        assertEquals(ChatState.USER_SPEAKING, machine.state)
        // 补发帧数 = 2 帧低电平 + 4 帧高电平 = 6 帧
        assertEquals(6, callbacks.audioFrames.size)
        // 补发完成后缓冲清空，后续帧正常逐帧发送
        machine.handleAudioLevel(0.3f, frame(0.3f))
        assertEquals(7, callbacks.audioFrames.size)
    }

    @Test
    fun `预触发缓冲容量上限 超出时丢弃最旧帧`() {
        createMachine()
        // 送入超过容量的低电平帧，缓冲应只保留最近 PRE_ROLL_FRAMES 帧
        repeat(ChatStateMachine.PRE_ROLL_FRAMES + 4) { machine.handleAudioLevel(0.01f, frame(0.01f)) }
        assertTrue(callbacks.audioFrames.isEmpty())
        repeat(4) { machine.handleAudioLevel(0.05f, frame(0.05f)) }
        assertEquals(ChatState.USER_SPEAKING, machine.state)
        // 超过容量的帧进入缓冲，只保留最近 PRE_ROLL_FRAMES 帧
        assertEquals(ChatStateMachine.PRE_ROLL_FRAMES, callbacks.audioFrames.size)
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
    fun `listen stop 消息携带 session_id`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        machine.setState(ChatState.IDLE)
        val stop = callbacks.textMessages.filterIsInstance<ListenMessage>().last()
        assertEquals("stop", stop.state)
        assertEquals(SESSION_ID, stop.sessionId)
    }

    @Test
    fun `静音启动计时 到期后进入 AI_SPEAKING 并发送 listen stop`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        // 需要连续 5 帧低于阈值才启动计时（防抖机制）
        repeat(4) {
            machine.handleAudioLevel(0.01f, frame(0.01f))
            assertEquals(0, scheduler.scheduleCount) // 前 4 帧未触发
        }
        machine.handleAudioLevel(0.01f, frame(0.01f)) // 第 5 帧触发
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
        // 连续 5 帧静音触发计时
        repeat(5) { machine.handleAudioLevel(0.01f, frame(0.01f)) }
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
        // 连续 5 帧触发计时
        repeat(5) { machine.handleAudioLevel(0.01f, frame(0.01f)) }
        assertEquals(1, scheduler.scheduleCount)
        // 继续静音帧不重复触发
        repeat(3) { machine.handleAudioLevel(0.01f, frame(0.01f)) }
        assertEquals(1, scheduler.scheduleCount)
    }

    // ---------------- AI_SPEAKING 打断 ----------------

    @Test
    fun `AI_SPEAKING 电平超打断阈值发送 abort 转 USER_SPEAKING`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        // 需要连续 3 帧超过打断阈值才触发（防抖机制）
        machine.handleAudioLevel(0.2f, frame(0.2f))
        assertEquals(ChatState.AI_SPEAKING, machine.state) // 第 1 帧，未触发
        machine.handleAudioLevel(0.2f, frame(0.2f))
        assertEquals(ChatState.AI_SPEAKING, machine.state) // 第 2 帧，未触发
        machine.handleAudioLevel(0.2f, frame(0.2f))
        assertEquals(ChatState.USER_SPEAKING, machine.state) // 第 3 帧，触发
        // 打断时先发 abort，随后进入 USER_SPEAKING 再发 listen start
        val abort = callbacks.textMessages.filterIsInstance<AbortMessage>().last()
        assertEquals(SESSION_ID, abort.sessionId)
        // AI_SPEAKING 期间帧缓存进预触发缓冲，打断时补发缓存帧（3 帧）+ 后续帧，
        // 保证打断句首完整（不丢打断确认期 + onset 前导帧）
        assertEquals(3, callbacks.audioFrames.size)
        // listen start 携带 session_id
        val listen = callbacks.textMessages.filterIsInstance<ListenMessage>().last()
        assertEquals("start", listen.state)
        assertEquals(SESSION_ID, listen.sessionId)
    }

    @Test
    fun `AI_SPEAKING 期间音频帧不发送给服务器`() {
        createMachine()
        // 先进入 USER_SPEAKING
        repeat(4) { machine.handleAudioLevel(0.05f, frame(0.05f)) }
        assertEquals(ChatState.USER_SPEAKING, machine.state)
        
        // 进入 AI_SPEAKING
        machine.setState(ChatState.AI_SPEAKING)
        assertEquals(ChatState.AI_SPEAKING, machine.state)
        
        // 清空之前的发送记录
        callbacks.audioFrames.clear()
        
        // AI_SPEAKING期间发送音频帧
        machine.handleAudioLevel(0.05f, frame(0.05f))
        machine.handleAudioLevel(0.05f, frame(0.05f))
        
        // 验证没有音频帧被发送
        assertEquals(0, callbacks.audioFrames.size)
    }

    @Test
    fun `打断 AI 时补发预触发缓冲帧 保证打断句首完整`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        // 先送超过容量的低电平帧（AI 正常说话），再送 3 帧高电平触发打断
        repeat(ChatStateMachine.PRE_ROLL_INTERRUPT_FRAMES + 2) { machine.handleAudioLevel(0.05f, frame(0.05f)) }
        repeat(3) { machine.handleAudioLevel(0.2f, frame(0.2f)) }
        assertEquals(ChatState.USER_SPEAKING, machine.state)
        // 缓冲容量上限为 PRE_ROLL_INTERRUPT_FRAMES，打断时补发最近 16 帧（含 3 帧打断确认）
        assertEquals(ChatStateMachine.PRE_ROLL_INTERRUPT_FRAMES, callbacks.audioFrames.size)
        // 进入 USER_SPEAKING 后续帧正常逐帧发送，缓冲已清空不重复补发
        machine.handleAudioLevel(0.3f, frame(0.3f))
        assertEquals(ChatStateMachine.PRE_ROLL_INTERRUPT_FRAMES + 1, callbacks.audioFrames.size)
    }

    @Test
    fun `AI_SPEAKING 瞬时高电平不触发打断`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        // 单帧高电平（回声尖峰）不应触发打断
        machine.handleAudioLevel(0.2f, frame(0.2f))
        assertEquals(ChatState.AI_SPEAKING, machine.state)
        assertTrue(callbacks.textMessages.none { it is AbortMessage })
        // 连续 2 帧后恢复低电平，计数器重置
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.05f, frame(0.05f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        assertEquals(ChatState.AI_SPEAKING, machine.state)
        assertTrue(callbacks.textMessages.none { it is AbortMessage })
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
        // 连续 5 帧静音触发计时
        repeat(5) { machine.handleAudioLevel(0.01f, frame(0.01f)) }
        assertEquals(1, scheduler.scheduleCount)
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

    // ---------------- onStateChanged 钩子（通话页星河动画的数据源） ----------------

    @Test
    fun `状态迁移时 onStateChanged 恰好触发一次`() {
        createMachine()
        val states = mutableListOf<ChatState>()
        machine.onStateChanged = { states.add(it) }

        machine.setState(ChatState.USER_SPEAKING)
        machine.setState(ChatState.AI_SPEAKING)
        machine.setState(ChatState.IDLE)

        assertEquals(
            listOf(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING, ChatState.IDLE),
            states,
        )
    }

    @Test
    fun `同态 setState 不触发 onStateChanged`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        var count = 0
        machine.onStateChanged = { count++ }

        machine.setState(ChatState.USER_SPEAKING)
        machine.setState(ChatState.USER_SPEAKING)

        assertEquals(0, count)
    }

    @Test
    fun `电平驱动的迁移同样触发 onStateChanged`() {
        createMachine()
        val states = mutableListOf<ChatState>()
        machine.onStateChanged = { states.add(it) }

        repeat(4) { machine.handleAudioLevel(0.05f, frame(0.05f)) }

        assertEquals(listOf(ChatState.USER_SPEAKING), states)
    }

    private companion object {
        const val SESSION_ID = "test-session"
    }
}
