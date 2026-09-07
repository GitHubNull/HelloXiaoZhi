package org.oxff.helloxiaozhi.controller

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxff.helloxiaozhi.chat.ChatRole
import org.oxff.helloxiaozhi.chat.ChatStateMachine
import org.oxff.helloxiaozhi.data.AppData
import org.oxff.helloxiaozhi.data.Bot
import org.oxff.helloxiaozhi.data.BotRepository
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
}
