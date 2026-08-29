package org.oxff.helloxiaozhi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxff.helloxiaozhi.util.DirectExecutor
import org.oxff.helloxiaozhi.util.SilenceScheduler

/**
 * ChatStateMachine 状态迁移单元测试（对齐参考 APP auto 模式：服务器端 VAD 驱动）。
 *
 * 测试范围：
 *  - IDLE / USER_SPEAKING 状态直接发送音频帧（无客户端 VAD 阈值检测）
 *  - AI_SPEAKING 状态完全不上行（由 XiaoZhiController 的 isAiPlaying 控制）
 *  - 状态迁移消息副作用（仅 ChatEvent；listen start/stop 由通话生命周期管理）
 *  - onStateChanged 钩子
 *
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

    // ---------------- IDLE 状态（服务器端 VAD 检测） ----------------

    @Test
    fun `IDLE 状态直接发送所有音频帧（无客户端 VAD 阈值检测）`() {
        createMachine()
        // IDLE 状态：直接发送所有帧（服务器端 VAD 检测用户说话）
        machine.handleAudioLevel(0.01f, frame(0.01f))
        machine.handleAudioLevel(0.05f, frame(0.05f))
        machine.handleAudioLevel(0.3f, frame(0.3f))
        assertEquals(3, callbacks.audioFrames.size)
        assertEquals(ChatState.IDLE, machine.state)
    }

    @Test
    fun `IDLE 状态不发送 listen start（等待服务器端 VAD 触发）`() {
        createMachine()
        machine.handleAudioLevel(0.05f, frame(0.05f))
        assertTrue(callbacks.textMessages.isEmpty())
        assertEquals(ChatState.IDLE, machine.state)
    }

    // ---------------- USER_SPEAKING 状态 ----------------

    @Test
    fun `USER_SPEAKING 状态持续发送音频帧`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        machine.handleAudioLevel(0.3f, frame(0.3f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        assertEquals(2, callbacks.audioFrames.size)
        assertEquals(ChatState.USER_SPEAKING, machine.state)
    }

    @Test
    fun `进入 USER_SPEAKING 不发送 listen start 只触发事件`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        // listen start 由 XiaoZhiController.startVoiceCall 发送，状态迁移不重复发送（避免重置服务器监听）
        assertTrue(callbacks.textMessages.filterIsInstance<ListenMessage>().isEmpty())
        assertTrue(callbacks.events.contains(ChatEvent.USER_START_SPEAKING))
    }

    @Test
    fun `离开 USER_SPEAKING 不发送 listen stop 只触发事件`() {
        createMachine()
        machine.setState(ChatState.USER_SPEAKING)
        machine.setState(ChatState.IDLE)
        // listen stop 由 XiaoZhiController.stopVoiceCall 发送
        assertTrue(callbacks.textMessages.filterIsInstance<ListenMessage>().isEmpty())
        assertTrue(callbacks.events.contains(ChatEvent.USER_STOP_SPEAKING))
    }

    // ---------------- AI_SPEAKING 状态 ----------------

    @Test
    fun `AI_SPEAKING 状态完全不上行音频帧`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        machine.handleAudioLevel(0.05f, frame(0.05f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        assertEquals(0, callbacks.audioFrames.size)
        assertEquals(ChatState.AI_SPEAKING, machine.state)
    }

    @Test
    fun `进入 AI_SPEAKING 触发 AI_START_SPEAKING 事件`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        assertTrue(callbacks.events.contains(ChatEvent.AI_START_SPEAKING))
    }

    @Test
    fun `离开 AI_SPEAKING 触发 AI_STOP_SPEAKING 事件`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        machine.setState(ChatState.IDLE)
        assertTrue(callbacks.events.contains(ChatEvent.AI_STOP_SPEAKING))
    }

    // ---------------- 状态迁移 ----------------

    @Test
    fun `完整对话流程 IDLE - USER_SPEAKING - AI_SPEAKING - IDLE`() {
        createMachine()
        // IDLE：直接发送音频帧
        machine.handleAudioLevel(0.05f, frame(0.05f))
        assertEquals(1, callbacks.audioFrames.size)
        assertEquals(ChatState.IDLE, machine.state)

        // 服务器端 VAD 检测到用户说话：进入 USER_SPEAKING（不重发 listen start）
        machine.setState(ChatState.USER_SPEAKING)
        assertEquals(ChatState.USER_SPEAKING, machine.state)
        assertTrue(callbacks.textMessages.filterIsInstance<ListenMessage>().isEmpty())

        // 用户说话中：持续发送音频帧
        machine.handleAudioLevel(0.3f, frame(0.3f))
        assertEquals(2, callbacks.audioFrames.size)

        // 服务器发送 TTS start：进入 AI_SPEAKING
        machine.setState(ChatState.AI_SPEAKING)
        assertEquals(ChatState.AI_SPEAKING, machine.state)
        assertTrue(callbacks.events.contains(ChatEvent.AI_START_SPEAKING))

        // AI 播放中：完全不上行
        machine.handleAudioLevel(0.05f, frame(0.05f))
        assertEquals(2, callbacks.audioFrames.size)

        // AI 播放完成：回到 IDLE
        machine.setState(ChatState.IDLE)
        assertEquals(ChatState.IDLE, machine.state)
        assertTrue(callbacks.events.contains(ChatEvent.AI_STOP_SPEAKING))
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

    private companion object {
        const val SESSION_ID = "test-session"
    }
}
