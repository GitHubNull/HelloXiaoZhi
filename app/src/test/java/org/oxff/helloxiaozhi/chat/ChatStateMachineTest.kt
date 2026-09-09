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

        /** 跳类型调用时序（验证 abort / 句首补发 / 事件的先后顺序） */
        val sequence = mutableListOf<String>()

        override fun sendAudioData(frame: ShortArray) {
            audioFrames.add(frame)
            sequence.add("audio")
        }

        override fun sendTextData(message: Any) {
            textMessages.add(message)
            sequence.add("text:${message::class.java.simpleName}")
        }

        override fun getSessionId(): String = SESSION_ID

        override fun onEvent(event: ChatEvent) {
            events.add(event)
            sequence.add("event:$event")
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
        machine.handleAudioLevel(0.06f, frame(0.06f))
        assertEquals(0, callbacks.audioFrames.size)
        assertEquals(ChatState.AI_SPEAKING, machine.state)
    }

    // ---------------- 客户端本地打断检测（barge-in） ----------------

    @Test
    fun `AI_SPEAKING 连续三帧超阈值触发本地打断并发 Abort`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        callbacks.textMessages.clear()

        // 前两帧未达连续确认数，不触发
        machine.handleAudioLevel(0.15f, frame(0.15f))
        machine.handleAudioLevel(0.15f, frame(0.15f))
        assertEquals(ChatState.AI_SPEAKING, machine.state)
        assertTrue(callbacks.textMessages.none { it is AbortMessage })

        // 第三帧达到连续 3 帧（180ms）→ 确认打断
        machine.handleAudioLevel(0.15f, frame(0.15f))
        assertEquals(ChatState.USER_SPEAKING, machine.state)
        assertTrue(callbacks.textMessages.any { it is AbortMessage })
    }

    @Test
    fun `AI_SPEAKING 期间 AEC 残留回声电平不触发打断也不上行`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        callbacks.textMessages.clear()

        // 真机实测：VOICE_COMMUNICATION + 硬件 AEC 下 AI 播放期间残留电平 0.011~0.06，
        // 连续多帧也不得误触发打断，否则 AI 会被自己的 TTS 回声打断
        repeat(30) { machine.handleAudioLevel(0.06f, frame(0.06f)) }

        assertEquals(ChatState.AI_SPEAKING, machine.state)
        assertEquals(0, callbacks.audioFrames.size)
        assertTrue(callbacks.textMessages.none { it is AbortMessage })
    }

    @Test
    fun `打断计数被低电平帧重置（过滤回声瞬时尖峰）`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)

        machine.handleAudioLevel(0.15f, frame(0.15f))
        machine.handleAudioLevel(0.15f, frame(0.15f))
        machine.handleAudioLevel(0.02f, frame(0.02f)) // 尖峰中断，计数重置
        machine.handleAudioLevel(0.15f, frame(0.15f))
        machine.handleAudioLevel(0.15f, frame(0.15f))

        // 未达成连续 3 帧，不应打断
        assertEquals(ChatState.AI_SPEAKING, machine.state)
    }

    @Test
    fun `打断时补发预触发缓冲的句首帧避免只识别后半句`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)

        // AI 播放期间缓存 5 帧低电平音频（不上行）
        repeat(5) { machine.handleAudioLevel(0.03f, frame(0.03f)) }
        assertEquals(0, callbacks.audioFrames.size)

        // 用户开口：连续 3 帧高电平确认打断
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.2f, frame(0.2f))

        // 缓存的 5 帧 + 打断确认的 3 帧 = 8 帧全部补发，句首不丢
        assertEquals(ChatState.USER_SPEAKING, machine.state)
        assertEquals(8, callbacks.audioFrames.size)
    }

    @Test
    fun `预触发缓冲容量上限 16 帧（约 960ms 句首轻声）`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)

        // 30 帧低电平 + 3 帧打断确认帧共 33 帧压入，环形缓冲只保留最后 16 帧
        repeat(30) { machine.handleAudioLevel(0.03f, frame(0.03f)) }
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.2f, frame(0.2f))

        assertEquals(16, callbacks.audioFrames.size)
    }

    @Test
    fun `打断时序为 abort 先于句首补发与 USER_START_SPEAKING 事件`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        callbacks.sequence.clear()

        machine.handleAudioLevel(0.03f, frame(0.03f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.2f, frame(0.2f))

        // 关键时序：abort 先发出打断服务器 TTS，
        // 再补发句首音频，最后才触发 USER_START_SPEAKING（外部据此 pausePlayback）。
        // 其中 AI_STOP_SPEAKING 先于句首补发：外部在该事件里重发 listen start，
        // 而服务器必须先收到 listen start 才会处理上行音频
        val abortIndex = callbacks.sequence.indexOf("text:AbortMessage")
        val aiStopIndex = callbacks.sequence.indexOf("event:AI_STOP_SPEAKING")
        val firstAudioIndex = callbacks.sequence.indexOf("audio")
        val userStartIndex = callbacks.sequence.indexOf("event:USER_START_SPEAKING")

        assertTrue(abortIndex >= 0)
        assertTrue(abortIndex < aiStopIndex)
        assertTrue(aiStopIndex < firstAudioIndex)
        assertTrue(firstAudioIndex < userStartIndex)
    }

    @Test
    fun `状态迁移重置打断计数避免跳状态误触发`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        // 外部强制迁移（如 tts stop 后回 IDLE）应重置计数
        machine.setState(ChatState.IDLE)
        machine.setState(ChatState.AI_SPEAKING)
        machine.handleAudioLevel(0.2f, frame(0.2f))

        // 若计数未重置，此处会误触发打断
        assertEquals(ChatState.AI_SPEAKING, machine.state)
    }

    @Test
    fun `reset 清空预触发缓冲不泄漏到下一轮通话`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        repeat(5) { machine.handleAudioLevel(0.03f, frame(0.03f)) }

        machine.reset()
        callbacks.audioFrames.clear()

        machine.setState(ChatState.AI_SPEAKING)
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.2f, frame(0.2f))
        machine.handleAudioLevel(0.2f, frame(0.2f))

        // 只补发本轮的 3 帧，上一轮的 5 帧已被 reset 清空
        assertEquals(3, callbacks.audioFrames.size)
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

    @Test
    fun `Barge-in 迁移 AI_SPEAKING 到 USER_SPEAKING 事件顺序为 AI_STOP 后 USER_START`() {
        createMachine()
        machine.setState(ChatState.AI_SPEAKING)
        callbacks.events.clear()

        machine.setState(ChatState.USER_SPEAKING)

        assertEquals(
            listOf(ChatEvent.AI_STOP_SPEAKING, ChatEvent.USER_START_SPEAKING),
            callbacks.events,
        )
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
