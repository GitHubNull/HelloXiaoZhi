package org.oxff.helloxiaozhi.controller

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxff.helloxiaozhi.chat.ChatRole
import org.oxff.helloxiaozhi.chat.ChatState
import org.oxff.helloxiaozhi.chat.ChatStateMachine
import org.oxff.helloxiaozhi.data.AppData
import org.oxff.helloxiaozhi.data.Bot
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.music.MusicActionMapper
import org.oxff.helloxiaozhi.music.MusicLibrary
import org.oxff.helloxiaozhi.music.MusicPlayer
import org.oxff.helloxiaozhi.music.MusicSource
import org.oxff.helloxiaozhi.music.MusicTrack
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * MessageDispatcher 结束语检测单元测试。
 *
 * 测试范围：
 *  - AI 回复包含结束语关键词时触发 onAiFarewell 回调
 *  - AI 回复不包含结束语关键词时不触发回调
 *  - 支持中英文结束语
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class MessageDispatcherTest {

    private lateinit var gson: Gson
    private lateinit var dispatcher: MessageDispatcher
    private lateinit var repository: BotRepository
    private lateinit var stateMachine: ChatStateMachine

    private var farewellTriggered = false

    // Barge-in 与唤醒词打断测试辅助
    private var bargeInCount = 0
    private var wakeWordInterruptCount = 0
    private var userStartSpeakingCount = 0
    private val receivedChatMessages = mutableListOf<ChatRole>()

    @Before
    fun setup() {
        gson = Gson()
        // 创建临时文件用于测试
        val tempFile = File.createTempFile("test", ".json")
        tempFile.deleteOnExit()

        // 创建包含一个测试机器人的 Repository
        repository = BotRepository(
            file = tempFile,
            gson = gson,
            seedFactory = {
                AppData(
                    bots = mutableListOf(
                        Bot(
                            id = "bot_1",
                            name = "测试机器人",
                            avatarText = "测",
                            avatarColorIndex = 0,
                            tags = listOf("测试"),
                            desc = "测试用机器人",
                            mac = "11:22:33:44:55:66",
                            activated = true,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                )
            }
        )

        stateMachine = ChatStateMachine(
            uiExecutor = org.oxff.helloxiaozhi.util.DirectExecutor(),
            scheduler = object : org.oxff.helloxiaozhi.util.SilenceScheduler {
                override fun schedule(delayMs: Long, action: () -> Unit) {}
                override fun cancel() {}
            },
            callbacks = object : ChatStateMachine.Callbacks {
                override fun sendAudioData(frame: ShortArray) {}
                override fun sendTextData(message: Any) {}
                override fun getSessionId(): String = "test-session"
                override fun onEvent(event: org.oxff.helloxiaozhi.chat.ChatEvent) {}
            }
        )

        dispatcher = MessageDispatcher(gson, repository, Handler(Looper.getMainLooper()), stateMachine)
        farewellTriggered = false

        // Barge-in 与唤醒词打断测试辅助回调
        bargeInCount = 0
        wakeWordInterruptCount = 0
        userStartSpeakingCount = 0
        receivedChatMessages.clear()
        dispatcher.onBargeIn = { bargeInCount++ }
        dispatcher.onWakeWordInterrupt = { wakeWordInterruptCount++ }
        dispatcher.onUserStartSpeaking = { userStartSpeakingCount++ }
        dispatcher.onChatMessage = { _, message -> receivedChatMessages.add(message.role) }
    }

    @Test
    fun `AI 回复包含晚安时标记待处理结束语`() {
        val json = """{"type":"llm","text":"晚安，做个好梦"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
        assertTrue(dispatcher.consumePendingFarewell())
        assertTrue(!dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复包含拜拜时标记待处理结束语`() {
        val json = """{"type":"llm","text":"拜拜，下次再聊"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复包含再见时标记待处理结束语`() {
        val json = """{"type":"llm","text":"再见，祝你今天愉快"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复包含英文 bye 时标记待处理结束语`() {
        val json = """{"type":"llm","text":"Bye bye, see you next time"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复包含英文 goodbye 时标记待处理结束语`() {
        val json = """{"type":"llm","text":"Goodbye, have a nice day"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复包含英文 good night 时标记待处理结束语`() {
        val json = """{"type":"llm","text":"Good night, sweet dreams"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复不包含结束语时不标记待处理`() {
        val json = """{"type":"llm","text":"今天天气很好，适合出去玩"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(!dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复为空时不标记待处理`() {
        val json = """{"type":"llm","text":""}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(!dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复为 null 时不标记待处理`() {
        val json = """{"type":"llm"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(!dispatcher.pendingFarewell)
    }

    @Test
    fun `结束语检测不区分大小写`() {
        val json = """{"type":"llm","text":"BYE BYE"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `TTS 句子包含晚安时标记待处理结束语`() {
        val json = """{"type":"tts","state":"sentence_start","text":"晚安，做个好梦"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `TTS 句子包含拜拜时标记待处理结束语`() {
        val json = """{"type":"tts","state":"sentence_start","text":"拜拜，下次再聊"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `TTS 句子不包含结束语时不标记待处理`() {
        val json = """{"type":"tts","state":"sentence_start","text":"今天天气很好"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(!dispatcher.pendingFarewell)
    }

    @Test
    fun `TTS 句子以百分号开头时不标记待处理`() {
        val json = """{"type":"tts","state":"sentence_start","text":"%晚安"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(!dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复包含我先退下了时标记待处理结束语`() {
        val json = """{"type":"llm","text":"好的，那我先退下了"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复包含退下时标记待处理结束语`() {
        val json = """{"type":"llm","text":"小的退下了"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复包含我走了时标记待处理结束语`() {
        val json = """{"type":"llm","text":"那我先走了，有事叫我"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `AI 回复包含告辞时标记待处理结束语`() {
        val json = """{"type":"llm","text":"在下告辞了"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    @Test
    fun `TTS 句子包含我先退下了时标记待处理结束语`() {
        val json = """{"type":"tts","state":"sentence_start","text":"好的，那我先退下了"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dispatcher.pendingFarewell)
    }

    // ---------------- Barge-in（AI 说话期间用户打断） ----------------

    @Test
    fun `AI_SPEAKING 时收到 STT 触发 onBargeIn 并迁移 USER_SPEAKING`() {
        stateMachine.setState(ChatState.AI_SPEAKING)

        val json = """{"type":"stt","text":"今天天气怎么样"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, bargeInCount)
        assertEquals(1, userStartSpeakingCount)
        assertEquals(ChatState.USER_SPEAKING, stateMachine.state)
        // 用户语音应落库
        assertEquals(1, receivedChatMessages.size)
    }

    @Test
    fun `AI_SPEAKING 时收到 STT 清除 pendingFarewell`() {
        // 先通过 llm 结束语标记 pendingFarewell
        dispatcher.handleTextMessage("""{"type":"llm","text":"晚安，做个好梦"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dispatcher.pendingFarewell)

        // AI 说结束语过程中用户打断
        stateMachine.setState(ChatState.AI_SPEAKING)
        dispatcher.handleTextMessage("""{"type":"stt","text":"等等别挂"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // pendingFarewell 已被清除，不会触发自动挂断
        assertTrue(!dispatcher.pendingFarewell)
        assertTrue(!dispatcher.consumePendingFarewell())
        assertEquals(1, bargeInCount)
    }

    @Test
    fun `IDLE 时收到 STT 不触发 onBargeIn`() {
        val json = """{"type":"stt","text":"你好"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, bargeInCount)
        assertEquals(1, userStartSpeakingCount)
        assertEquals(ChatState.USER_SPEAKING, stateMachine.state)
    }

    // ---------------- 唤醒词打断本地音乐 ----------------

    @Test
    fun `本地音乐播放中收到非唤醒词开头的 STT 直接忽略`() {
        dispatcher.isLocalMusicPlayingProvider = { true }

        val json = """{"type":"stt","text":"今天天气怎么样"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // 音乐照播：不打断、不落库、不迁移状态
        assertEquals(0, wakeWordInterruptCount)
        assertEquals(0, receivedChatMessages.size)
        assertEquals(0, userStartSpeakingCount)
        assertEquals(ChatState.IDLE, stateMachine.state)
    }

    @Test
    fun `本地音乐播放中收到唤醒词开头的 STT 打断音乐并处理剩余文本`() {
        dispatcher.isLocalMusicPlayingProvider = { true }

        val json = """{"type":"stt","text":"阿妹阿妹，今天天气怎么样"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // 触发音乐打断，剩余文本落库并迁移状态
        assertEquals(1, wakeWordInterruptCount)
        assertEquals(1, receivedChatMessages.size)
        assertEquals(1, userStartSpeakingCount)
        assertEquals(ChatState.USER_SPEAKING, stateMachine.state)
    }

    @Test
    fun `本地音乐播放中收到仅唤醒词的 STT 打断音乐后不处理后续`() {
        dispatcher.isLocalMusicPlayingProvider = { true }

        val json = """{"type":"stt","text":"阿妹阿妹"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // 打断音乐但无剩余文本：不落库、不迁移状态
        assertEquals(1, wakeWordInterruptCount)
        assertEquals(0, receivedChatMessages.size)
        assertEquals(0, userStartSpeakingCount)
        assertEquals(ChatState.IDLE, stateMachine.state)
    }

    @Test
    fun `唤醒词前缀剥离支持小智小智别名`() {
        dispatcher.isLocalMusicPlayingProvider = { true }

        val json = """{"type":"stt","text":"小智小智今天天气"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, wakeWordInterruptCount)
        assertEquals(1, receivedChatMessages.size)
    }

    @Test
    fun `唤醒词前缀剥离后剩余文本原样落库`() {
        dispatcher.isLocalMusicPlayingProvider = { true }
        val contents = mutableListOf<String>()
        dispatcher.onChatMessage = { _, message -> contents.add(message.content) }

        // 本用例未注入 musicActionMapper，只验证唤醒词剥离本身；
        // 因此选用非控制指令文本（控制指令在真实环境会被本地拦截而不落库）
        // 「阿妹阿妹，今天天气怎么样」→ 落库内容为「今天天气怎么样」（唤醒词与逗号被剥离）
        dispatcher.handleTextMessage("""{"type":"stt","text":"阿妹阿妹，今天天气怎么样"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("今天天气怎么样"), contents)

        // 「小智小智今天天气」→ 落库内容为「今天天气」（无分隔符也正确剥离）
        contents.clear()
        dispatcher.handleTextMessage("""{"type":"stt","text":"小智小智今天天气"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("今天天气"), contents)
    }

    @Test
    fun `clearPendingFarewell 清除残留结束语标志避免误挂断`() {
        dispatcher.handleTextMessage("""{"type":"llm","text":"晚安，做个好梦"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dispatcher.pendingFarewell)

        // 用户本地电平打断（不经 stt）时，外部通过该方法清除标志
        dispatcher.clearPendingFarewell()

        assertTrue(!dispatcher.pendingFarewell)
        assertTrue(!dispatcher.consumePendingFarewell())
    }

    @Test
    fun `非音乐播放时唤醒词开头的 STT 走正常流程不打断`() {
        dispatcher.isLocalMusicPlayingProvider = { false }

        val json = """{"type":"stt","text":"阿妹阿妹，你好"}"""
        dispatcher.handleTextMessage(json, "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // 不在音乐播放中：不触发打断，原文落库并迁移状态
        assertEquals(0, wakeWordInterruptCount)
        assertEquals(1, receivedChatMessages.size)
        assertEquals(ChatState.USER_SPEAKING, stateMachine.state)
    }

    // ---------------- 音乐控制指令不得污染聊天 ----------------

    @Test
    fun `音乐播放中唤醒词加停止指令本地执行且不落库`() {
        // 真机实测：说「阿妹阿妹，别唱了」后音乐停了，但这句话被当作聊天内容
        // 落库并上行，服务器 AI 接着围绕"别唱了"闲聊，体验割裂
        val (mapper, player) = newMapperWithTracks()
        dispatcher.musicActionMapper = mapper
        dispatcher.isLocalMusicPlayingProvider = { true }
        var localHandled = 0
        dispatcher.onLocalMusicHandled = { localHandled++ }

        dispatcher.handleTextMessage("""{"type":"stt","text":"阿妹阿妹，别唱了"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, player.stopCount) // 本地已停音乐
        assertEquals(1, localHandled) // 发 abort + 开抑制窗口
        assertEquals(0, receivedChatMessages.size) // 不得落库为聊天内容
        assertEquals(0, userStartSpeakingCount) // 不迁移状态
        assertEquals(ChatState.IDLE, stateMachine.state)
    }

    @Test
    fun `音乐播放中唤醒词加切歌指令不得停止播放`() {
        // 回归：旧实现无条件先调 onWakeWordInterrupt 停音乐，
        // "阿妹阿妹，下一首" 会先终止播放、再执行已无意义的 next()
        val (mapper, player) = newMapperWithTracks()
        dispatcher.musicActionMapper = mapper
        dispatcher.isLocalMusicPlayingProvider = { true }

        dispatcher.handleTextMessage("""{"type":"stt","text":"阿妹阿妹，下一首"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, player.nextCount)
        assertEquals(0, player.stopCount) // 关键：不得停止播放
        assertEquals(0, wakeWordInterruptCount) // 不触发停音乐回调
        assertEquals(0, receivedChatMessages.size)
    }

    @Test
    fun `音乐播放中唤醒词加点歌指令换歌且不落库`() {
        val (mapper, player) = newMapperWithTracks()
        dispatcher.musicActionMapper = mapper
        dispatcher.isLocalMusicPlayingProvider = { true }
        var localHandled = 0
        dispatcher.onLocalMusicHandled = { localHandled++ }

        dispatcher.handleTextMessage("""{"type":"stt","text":"阿妹阿妹，播放韩宝仪的歌"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("想要潇洒的离开", player.lastPlayedTrack?.title)
        assertEquals(1, localHandled)
        assertEquals(0, receivedChatMessages.size)
    }

    @Test
    fun `音乐播放中唤醒词加普通提问仍停音乐并落库`() {
        // 非控制指令 → 用户是想对话：停音乐后走正常聊天流程
        val (mapper, _) = newMapperWithTracks()
        dispatcher.musicActionMapper = mapper
        dispatcher.isLocalMusicPlayingProvider = { true }
        var localHandled = 0
        dispatcher.onLocalMusicHandled = { localHandled++ }

        dispatcher.handleTextMessage("""{"type":"stt","text":"阿妹阿妹，今天天气怎么样"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, wakeWordInterruptCount) // 停音乐由该回调负责
        assertEquals(0, localHandled)
        assertEquals(1, receivedChatMessages.size) // 正常落库
        assertEquals(ChatState.USER_SPEAKING, stateMachine.state)
    }

    @Test
    fun `非音乐播放时控制类口语不得吞掉聊天`() {
        // "别说话" 在无音乐播放时是正常聊天内容，不得被 STOP_KEYWORDS 拦截
        val (mapper, player) = newMapperWithTracks()
        dispatcher.musicActionMapper = mapper
        dispatcher.isLocalMusicPlayingProvider = { false }
        var localHandled = 0
        dispatcher.onLocalMusicHandled = { localHandled++ }

        dispatcher.handleTextMessage("""{"type":"stt","text":"你先别说话，我说完再说"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(mapper.isControlCommand("你先别说话，我说完再说"))
        assertEquals(0, player.stopCount)
        assertEquals(0, localHandled) // 不得误发 abort
        assertEquals(1, receivedChatMessages.size) // 正常落库
        assertEquals(ChatState.USER_SPEAKING, stateMachine.state)
    }

    // ---------------- 服务器回复抑制窗口 ----------------

    @Test
    fun `抑制窗口内 llm 与 tts 文本被丢弃不落库`() {
        dispatcher.isReplySuppressedProvider = { true }

        dispatcher.handleTextMessage("""{"type":"llm","text":"好的，我不唱了"}""", "bot_1")
        dispatcher.handleTextMessage(
            """{"type":"tts","state":"sentence_start","text":"好的，我不唱了"}""",
            "bot_1",
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, receivedChatMessages.size)
        assertTrue(!dispatcher.pendingFarewell)
    }

    @Test
    fun `抑制窗口内 tts start 不触发 onTtsStart`() {
        dispatcher.isReplySuppressedProvider = { true }
        var ttsStartCount = 0
        dispatcher.onTtsStart = { ttsStartCount++ }

        dispatcher.handleTextMessage("""{"type":"tts","state":"start"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, ttsStartCount)
        assertEquals(ChatState.IDLE, stateMachine.state)
    }

    @Test
    fun `用户新一轮真实说话清除回复抑制`() {
        var cleared = 0
        dispatcher.onClearReplySuppress = { cleared++ }
        dispatcher.isReplySuppressedProvider = { false }

        dispatcher.handleTextMessage("""{"type":"stt","text":"今天天气怎么样"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, cleared)
        assertEquals(1, receivedChatMessages.size)
    }

    @Test
    fun `音乐指令免疫期内无唤醒词同批 STT 尾巴被忽略`() {
        // 真机实测：「阿妹阿妹，别唱了」被服务器切出第二条 STT「这。」，
        // 此时音乐已停（isLocalMusicPlaying=false）但仍处抑制免疫期（isReplySuppressed=true）。
        // 旧实现因音乐已停绕过 handleSttDuringMusic、走 handleSttText 落库并清了抑制窗口，
        // 让 AI 围绕「这。」接话。免疫期内无唤醒词的 STT 必须忽略：
        // 不得落库、不得迁移状态、不得清除抑制窗口。
        dispatcher.isLocalMusicPlayingProvider = { false }
        dispatcher.isReplySuppressedProvider = { true }
        var cleared = 0
        dispatcher.onClearReplySuppress = { cleared++ }

        dispatcher.handleTextMessage("""{"type":"stt","text":"这。"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, cleared) // 不得清除抑制窗口
        assertEquals(0, receivedChatMessages.size) // 不得落库
        assertEquals(0, userStartSpeakingCount) // 不迁移状态
        assertEquals(ChatState.IDLE, stateMachine.state)
    }

    @Test
    fun `音乐指令免疫期内带唤醒词真实对话正常落库并清抑制`() {
        // 免疫期内用户以唤醒词重新开启真实对话（如问天气），应照常落库并清除抑制。
        // 这是「只在免疫期内忽略无唤醒词 STT」且不误伤真实对话的关键。
        dispatcher.isLocalMusicPlayingProvider = { false }
        dispatcher.isReplySuppressedProvider = { true }
        var cleared = 0
        dispatcher.onClearReplySuppress = { cleared++ }

        dispatcher.handleTextMessage("""{"type":"stt","text":"阿妹阿妹，今天天气怎么样"}""", "bot_1")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, cleared) // 真实对话清除抑制
        assertEquals(1, receivedChatMessages.size) // 正常落库
        assertEquals(ChatState.USER_SPEAKING, stateMachine.state)
    }

    // ---------------- 测试替身 ----------------

    /** 构造带真实 MusicActionMapper 的环境（曲库预置韩宝仪与周杰伦各一首） */
    private fun newMapperWithTracks(): Pair<MusicActionMapper, RecordingMusicPlayer> {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val player = RecordingMusicPlayer(context)
        val library = StubMusicLibrary(context)
        library.tracks = listOf(
            MusicTrack(
                id = "1", title = "想要潇洒的离开", artist = "韩宝仪", album = null,
                duration = 200000, path = "/music/a.mp3", source = MusicSource.LOCAL,
            ),
            MusicTrack(
                id = "2", title = "晴天", artist = "周杰伦", album = "范特西",
                duration = 200000, path = "/music/b.mp3", source = MusicSource.LOCAL,
            ),
        )
        return MusicActionMapper(player, library) to player
    }

    private class RecordingMusicPlayer(context: android.content.Context) : MusicPlayer(context) {
        var stopCount = 0
        var nextCount = 0
        var lastPlayedTrack: MusicTrack? = null

        override fun play(track: MusicTrack) {
            lastPlayedTrack = track
        }

        override fun stop() {
            stopCount++
        }

        override fun next(): Boolean {
            nextCount++
            return true
        }

        override fun previous(): Boolean = true
        override fun pause() {}
        override fun resume() {}
    }

    private class StubMusicLibrary(context: android.content.Context) : MusicLibrary(
        context,
        // 惰性创建内存 DB：避免 Robolectric 沙箱类加载器重复加载 SQLite native 库
        lazy {
            androidx.room.Room.inMemoryDatabaseBuilder(
                context,
                org.oxff.helloxiaozhi.data.db.MusicDatabase::class.java,
            ).allowMainThreadQueries().build()
        }.value,
    ) {
        var tracks: List<MusicTrack> = emptyList()

        override fun allTracks(): List<MusicTrack> = tracks

        override fun search(keyword: String): List<MusicTrack> {
            val lower = keyword.lowercase()
            return tracks.filter {
                it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower)
            }
        }

        override fun randomTrack(): MusicTrack? = tracks.randomOrNull()

        override fun randomByGenre(genre: String): MusicTrack? = tracks.randomOrNull()

        override fun tracksByArtist(artist: String): List<MusicTrack> {
            val lower = artist.lowercase()
            return tracks.filter { it.artist.lowercase().contains(lower) }
        }

        override fun searchByAlbum(album: String): List<MusicTrack> {
            val lower = album.lowercase()
            return tracks.filter { it.album?.lowercase()?.contains(lower) == true }
        }
    }
}
