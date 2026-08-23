package org.oxff.helloxiaozhi.chat

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 消息模型单元测试：上行消息序列化字段名（snake_case）
 * 与下行消息解析，对齐官方 xiaozhi 协议与 ref types/message.ts。
 */
class MessagesTest {

    private val gson = Gson()

    // ---------------- 上行消息序列化 ----------------

    @Test
    fun `hello 握手消息字段与协议一致`() {
        val json = JsonParser.parseString(gson.toJson(HelloMessage())).asJsonObject
        assertEquals("hello", json.get("type").asString)
        assertEquals(3, json.get("version").asInt)
        assertEquals("websocket", json.get("transport").asString)

        val audio = json.getAsJsonObject("audio_params")
        assertEquals("opus", audio.get("format").asString)
        assertEquals(16000, audio.get("sample_rate").asInt)
        assertEquals(1, audio.get("channels").asInt)
        assertEquals(60, audio.get("frame_duration").asInt)
    }

    @Test
    fun `listen start 与 stop 消息`() {
        val start = JsonParser.parseString(gson.toJson(ListenMessage.start())).asJsonObject
        assertEquals("listen", start.get("type").asString)
        assertEquals("start", start.get("state").asString)
        assertEquals("auto", start.get("mode").asString)

        val stop = JsonParser.parseString(gson.toJson(ListenMessage.stop())).asJsonObject
        assertEquals("stop", stop.get("state").asString)
    }

    @Test
    fun `文字聊天 detect 消息携带文本与 source`() {
        val json = JsonParser.parseString(
            gson.toJson(DetectMessage(text = "你好")),
        ).asJsonObject
        assertEquals("listen", json.get("type").asString)
        assertEquals("detect", json.get("state").asString)
        assertEquals("你好", json.get("text").asString)
        assertEquals("text", json.get("source").asString)
    }

    @Test
    fun `abort 消息携带 session_id`() {
        val json = JsonParser.parseString(gson.toJson(AbortMessage(sessionId = "s-1"))).asJsonObject
        assertEquals("abort", json.get("type").asString)
        assertEquals("s-1", json.get("session_id").asString)
    }

    // ---------------- 下行消息解析 ----------------

    @Test
    fun `解析 hello 响应 session_id 与服务器采样率`() {
        val hello = gson.fromJson(
            """{"type":"hello","session_id":"s-42",
                "audio_params":{"format":"opus","sample_rate":24000,
                                "channels":1,"frame_duration":60}}""",
            HelloResponse::class.java,
        )
        assertEquals("s-42", hello.sessionId)
        assertEquals(24000, hello.audioParams?.sampleRate)
    }

    @Test
    fun `解析 stt 与 llm 消息`() {
        val stt = gson.fromJson("""{"type":"stt","text":"今天天气怎么样"}""", SttMessage::class.java)
        assertEquals("今天天气怎么样", stt.text)

        val llm = gson.fromJson("""{"type":"llm","emotion":"happy","text":"[开心]"}""", LlmMessage::class.java)
        assertEquals("happy", llm.emotion)
        assertEquals("[开心]", llm.text)
    }

    @Test
    fun `解析 tts 状态消息与控制文本`() {
        val start = gson.fromJson("""{"type":"tts","state":"start"}""", TtsMessage::class.java)
        assertEquals(TtsMessage.STATE_START, start.state)

        val sentence = gson.fromJson(
            """{"type":"tts","state":"sentence_start","text":"%start"}""",
            TtsMessage::class.java,
        )
        assertEquals(TtsMessage.STATE_SENTENCE_START, sentence.state)
        assertEquals("%start", sentence.text)
    }

    @Test
    fun `缺失可选字段解析为 null 不抛异常`() {
        val stt = gson.fromJson("""{"type":"stt"}""", SttMessage::class.java)
        assertEquals(null, stt.text)
        assertEquals(null, stt.sessionId)

        val hello = gson.fromJson("""{"type":"hello"}""", HelloResponse::class.java)
        assertEquals(null, hello.sessionId)
        assertEquals(null, hello.audioParams)
    }
}
