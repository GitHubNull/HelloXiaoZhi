package org.oxff.helloxiaozhi.asr

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MockAsrServer 模拟服务器单元测试：
 * 验证小智协议握手、音频帧接收、listen 分段、STT 响应与错误注入。
 */
class MockAsrServerTest {

    private lateinit var server: MockAsrServer
    private val gson = Gson()

    /** 简易测试客户端：记录下行消息 */
    private inner class Client : WebSocketListener() {
        val messages = ConcurrentLinkedQueue<String>()
        val openLatch = CountDownLatch(1)
        var webSocket: WebSocket? = null

        fun connect() {
            val client = OkHttpClient()
            webSocket = client.newWebSocket(
                Request.Builder().url(server.wsUrl()).build(), this
            )
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            openLatch.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.add(text)
        }

        fun sendHello() {
            webSocket!!.send(gson.toJson(mapOf(
                "type" to "hello", "version" to 3, "transport" to "websocket"
            )))
        }

        fun sendListenStart() {
            webSocket!!.send(gson.toJson(mapOf(
                "type" to "listen", "state" to "start", "session_id" to "s"
            )))
        }

        fun sendListenStop() {
            webSocket!!.send(gson.toJson(mapOf(
                "type" to "listen", "state" to "stop", "session_id" to "s"
            )))
        }

        fun sendAudioFrame(amplitude: Int) {
            val frame = ShortArray(960) { amplitude.toShort() }
            val buffer = ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN)
            for (s in frame) buffer.putShort(s)
            webSocket!!.send(ByteString.of(*buffer.array()))
        }

        fun waitForMessageContaining(substr: String, timeoutMs: Long = 5000): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                for (m in messages) if (m.contains(substr)) return m
                Thread.sleep(20)
            }
            throw AssertionError("等待包含「$substr」的下行消息超时，已收到: $messages")
        }
    }

    private lateinit var client: Client

    @Before
    fun setUp() {
        server = MockAsrServer()
        server.start()
        client = Client()
        client.connect()
        assertTrue("连接应建立", client.openLatch.await(5, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        client.webSocket?.close(1000, "bye")
        server.stop()
    }

    @Test
    fun `hello 握手返回 session_id`() {
        client.sendHello()
        val response = client.waitForMessageContaining("\"type\":\"hello\"")
        assertTrue(response.contains("session_id"))
        assertEquals(MockAsrServer.MessageType.HELLO,
            server.lastMessageOfType(MockAsrServer.MessageType.HELLO)?.type)
    }

    @Test
    fun `listen 分段后返回默认 STT 响应`() {
        server.setDefaultSttResponse("今天天气怎么样")
        client.sendHello()
        client.sendListenStart()
        repeat(10) { client.sendAudioFrame(8000) }
        // 服务器端 VAD：收到首帧后 100ms 自动发送 STT（无需 listen stop）

        val stt = client.waitForMessageContaining("\"type\":\"stt\"")
        assertTrue(stt.contains("今天天气怎么样"))

        val segment = server.lastSegment()
        assertNotNull(segment)
        assertEquals(10, segment!!.frames.size)
        assertEquals("今天天气怎么样", segment.sttSent)
    }

    @Test
    fun `音频统计记录帧数与字节数`() {
        client.sendHello()
        client.sendListenStart()
        repeat(5) { client.sendAudioFrame(4000) }
        // 服务器端 VAD：收到首帧后 100ms 自动发送 STT 并落盘说话段
        // 等待 VAD 触发 + 所有帧到达服务器
        Thread.sleep(300)
        server.awaitStt()

        val stats = server.getAudioStats()
        assertEquals(5, stats.totalFrames)
        assertEquals(5L * 960 * 2, stats.totalBytes)
        assertEquals(1920, stats.averageFrameSize)
        assertTrue(stats.levelDistribution.isNotEmpty())
    }

    @Test
    fun `错误注入 无 STT 响应`() {
        server.setDefaultSttResponse("文本")
        server.setErrorInjection(MockAsrServer.ErrorConfig.NoSttResponse)
        client.sendHello()
        client.sendListenStart()
        client.sendAudioFrame(8000)
        // 服务器端 VAD：收到首帧后 100ms 触发，但 NoSttResponse 配置不发送 STT
        Thread.sleep(300)

        assertTrue("不应收到 stt 消息",
            client.messages.none { it.contains("\"type\":\"stt\"") })
    }

    @Test
    fun `错误注入 自定义错误 JSON`() {
        server.setDefaultSttResponse("文本")
        server.setErrorInjection(MockAsrServer.ErrorConfig.ErrorJson("{\"type\":\"error\",\"msg\":\"boom\"}"))
        client.sendHello()
        client.sendListenStart()
        client.sendAudioFrame(8000)
        // 服务器端 VAD：收到首帧后 100ms 触发，ErrorJson 配置发送错误 JSON

        assertNotNull(client.waitForMessageContaining("\"type\":\"error\""))
    }

    @Test
    fun `指纹映射优先于默认响应`() {
        // 构造一段已知指纹的音频：8 帧、振幅 8000
        val pcm = ShortArray(8 * 960) { 8000 }
        val fingerprint = AudioFingerprint.fromPcm(pcm)
        server.setSttMapping(mapOf(fingerprint to "映射文本"))
        server.setDefaultSttResponse("默认文本")

        client.sendHello()
        client.sendListenStart()
        repeat(8) { client.sendAudioFrame(8000) }
        // 服务器端 VAD：收到首帧后 100ms 自动发送 STT（无需 listen stop）

        val stt = client.waitForMessageContaining("\"type\":\"stt\"")
        assertTrue("应命中指纹映射（实际: $stt）", stt.contains("映射文本"))
    }
}
