package org.oxff.helloxiaozhi.asr

import com.google.gson.Gson
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 模拟 ASR 服务器：实现小智 WebSocket 协议的服务器端（基于 MockWebServer）。
 *
 * 行为对齐真实小智服务器（ref 后端 websocket_proxy.py）：
 *  - 客户端连接后先收 hello，回发携带 session_id 的 hello 应答
 *  - 收到二进制帧视为音频（测试链路为原始 PCM，生产链路为 Opus）
 *  - 收到 listen start/stop 划定一次「说话段」；stop 后按指纹匹配
 *    预设的 STT 文本并回发 stt / tts 消息
 *
 * 该服务器只依赖 okhttp3 + gson，可运行在 JVM 单元测试环境。
 */
class MockAsrServer(private val port: Int = 0) {

    /** 服务器收到的消息类型 */
    enum class MessageType { HELLO, LISTEN_START, LISTEN_STOP, DETECT, ABORT, AUDIO_FRAME, UNKNOWN }

    /** 服务器收到的消息记录 */
    data class ReceivedMessage(
        val timestamp: Long,
        val type: MessageType,
        val content: String?,
        val audioFrame: ShortArray? = null
    )

    /** 一次完整的说话段（listen start → stop 之间的音频） */
    data class ReceivedSegment(
        val frames: List<ShortArray>,
        val fingerprint: AudioFingerprint,
        val sttSent: String?,
        val startTime: Long,
        val stopTime: Long
    )

    /** 错误注入配置 */
    sealed class ErrorConfig {
        /** 不注入错误（默认） */
        object None : ErrorConfig()

        /** listen stop 后不返回 stt（模拟服务器无响应/识别失败） */
        object NoSttResponse : ErrorConfig()

        /** 收到 listen start 后直接断开连接（模拟服务器异常断线） */
        object CloseOnListenStart : ErrorConfig()

        /** 返回指定的错误 JSON（模拟服务器错误响应） */
        data class ErrorJson(val json: String) : ErrorConfig()
    }

    private val server = MockWebServer()
    private val gson = Gson()

    private val receivedMessages = ConcurrentLinkedQueue<ReceivedMessage>()
    private val segments = ConcurrentLinkedQueue<ReceivedSegment>()

    /** 当前说话段累积的帧（服务器单连接测试，无需多段并发） */
    private val currentFrames = mutableListOf<ShortArray>()
    private var segmentStartTime = 0L

    private val sttMapping = mutableMapOf<AudioFingerprint, String>()
    private var defaultSttResponse: String? = null
    @Volatile private var responseDelayMs = 0L
    private val errorConfig = AtomicReference<ErrorConfig>(ErrorConfig.None)

    private val sessionId = "mock-session-${System.currentTimeMillis()}"
    private val connectionOpened = CountDownLatch(1)
    private val sttLatch = AtomicReference(CountDownLatch(0))

    @Volatile private var clientSocket: WebSocket? = null

    /** 启动服务器（port=0 使用随机端口） */
    fun start() {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        server.start(port)
    }

    /** 停止服务器 */
    fun stop() {
        clientSocket?.close(1000, "server shutdown")
        try {
            server.shutdown()
        } catch (_: Exception) {
            // 已关闭或客户端异常断开时忽略
        }
    }

    /** 客户端连接地址（ws://） */
    fun wsUrl(): String = "ws://${server.hostName}:${server.port}/"

    /** 等待客户端连接建立 */
    fun awaitConnection(timeoutMs: Long = 5000): Boolean =
        connectionOpened.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** 等待最近一次 STT 响应发送完成（先等说话段落盘，保证 latch 已替换） */
    fun awaitStt(timeoutMs: Long = 10000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (segments.isEmpty()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(10)
        }
        return sttLatch.get().await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    // ---------------- 行为配置 ----------------

    /** 设置音频指纹到 STT 结果的映射 */
    fun setSttMapping(mapping: Map<AudioFingerprint, String>) {
        synchronized(sttMapping) {
            sttMapping.clear()
            sttMapping.putAll(mapping)
        }
    }

    /** 设置默认 STT 响应（未匹配到指纹时） */
    fun setDefaultSttResponse(text: String) {
        defaultSttResponse = text
    }

    /** 设置响应延迟（模拟网络/处理延迟） */
    fun setResponseDelay(ms: Long) {
        responseDelayMs = ms
    }

    /** 设置错误注入（模拟服务器错误） */
    fun setErrorInjection(config: ErrorConfig) {
        errorConfig.set(config)
    }

    // ---------------- 观测接口 ----------------

    /** 获取服务器收到的所有消息（按时间顺序） */
    fun getReceivedMessages(): List<ReceivedMessage> = receivedMessages.toList()

    /** 获取所有完成的说话段 */
    fun getSegments(): List<ReceivedSegment> = segments.toList()

    /** 获取最近一个说话段 */
    fun lastSegment(): ReceivedSegment? = segments.lastOrNull()

    /** 获取服务器收到的音频帧统计 */
    fun getAudioStats(): AudioStats {
        val frames = segments.flatMap { it.frames }
        val totalBytes = frames.sumOf { it.size * 2L }
        val avgSize = if (frames.isEmpty()) 0 else totalBytes.toInt() / frames.size

        // 帧间隔分布（基于 AUDIO_FRAME 消息时间戳）
        val audioTimestamps = receivedMessages
            .filter { it.type == MessageType.AUDIO_FRAME }
            .map { it.timestamp }
        val intervals = audioTimestamps.zipWithNext { a, b -> b - a }

        // 电平分布直方图（0.02 步长分桶）
        val distribution = mutableMapOf<String, Int>()
        for (frame in frames) {
            var sumAbs = 0.0
            for (s in frame) sumAbs += Math.abs(s.toInt())
            val level = if (frame.isEmpty()) 0.0 else sumAbs / frame.size / Short.MAX_VALUE
            val bucket = (level / 0.02).toInt()
            val key = "${"%.2f".format(bucket * 0.02)}-${"%.2f".format((bucket + 1) * 0.02)}"
            distribution[key] = (distribution[key] ?: 0) + 1
        }

        return AudioStats(
            totalFrames = frames.size,
            totalBytes = totalBytes,
            averageFrameSize = avgSize,
            frameIntervalMs = intervals,
            levelDistribution = distribution
        )
    }

    /** 收到的最近一条指定类型消息 */
    fun lastMessageOfType(type: MessageType): ReceivedMessage? =
        receivedMessages.lastOrNull { it.type == type }

    // ---------------- WebSocket 服务器端实现 ----------------

    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            clientSocket = webSocket
            connectionOpened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val parsed = try {
                gson.fromJson(text, Map::class.java)
            } catch (_: Exception) {
                null
            }
            val type = parsed?.get("type") as? String
            val state = parsed?.get("state") as? String

            val message = when {
                type == "hello" -> MessageType.HELLO
                type == "listen" && state == "start" -> MessageType.LISTEN_START
                type == "listen" && state == "stop" -> MessageType.LISTEN_STOP
                type == "listen" && state == "detect" -> MessageType.DETECT
                type == "abort" -> MessageType.ABORT
                else -> MessageType.UNKNOWN
            }
            receivedMessages.add(ReceivedMessage(System.currentTimeMillis(), message, text))

            when (message) {
                MessageType.HELLO -> sendHelloResponse(webSocket)
                MessageType.LISTEN_START -> handleListenStart(webSocket)
                MessageType.LISTEN_STOP -> handleListenStop(webSocket)
                else -> Unit
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = bytesToShorts(bytes.toByteArray())
            receivedMessages.add(
                ReceivedMessage(System.currentTimeMillis(), MessageType.AUDIO_FRAME, null, frame)
            )
            synchronized(currentFrames) {
                currentFrames.add(frame)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            clientSocket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            clientSocket = null
        }
    }

    private fun sendHelloResponse(webSocket: WebSocket) {
        val response = mapOf(
            "type" to "hello",
            "transport" to "websocket",
            "session_id" to sessionId,
            "audio_params" to mapOf(
                "format" to "opus",
                "sample_rate" to 16000,
                "channels" to 1,
                "frame_duration" to 60
            )
        )
        webSocket.send(gson.toJson(response))
    }

    private fun handleListenStart(webSocket: WebSocket) {
        when (val config = errorConfig.get()) {
            is ErrorConfig.CloseOnListenStart -> {
                webSocket.close(1011, "injected error")
                return
            }
            else -> Unit
        }
        // 不清空已收到的帧：客户端先补发预触发缓冲帧、再发 listen start，
        // 这些帧属于本说话段的句首音频（对齐真实服务器连续音频流语义）
        segmentStartTime = System.currentTimeMillis()
    }

    private fun handleListenStop(webSocket: WebSocket) {
        val frames = synchronized(currentFrames) {
            val copy = currentFrames.toList()
            currentFrames.clear()
            copy
        }
        val stopTime = System.currentTimeMillis()
        val fingerprint = AudioFingerprint.fromFrames(frames)

        // 先注册 latch 再落盘说话段：awaitStt 以「段已落盘」为等待条件，
        // 保证此时 sttLatch 已被替换为本段的真实 latch（避免竞态）
        val latch = CountDownLatch(1)
        sttLatch.set(latch)

        val sttText = resolveStt(fingerprint)
        segments.add(ReceivedSegment(frames, fingerprint, sttText, segmentStartTime, stopTime))

        val delay = responseDelayMs
        Thread {
            try {
                if (delay > 0) Thread.sleep(delay)
                when (val config = errorConfig.get()) {
                    is ErrorConfig.NoSttResponse -> Unit
                    is ErrorConfig.ErrorJson -> webSocket.send(config.json)
                    else -> {
                        if (sttText != null) {
                            // 对齐真实服务器时序：stt → tts start → sentence_start → stop
                            webSocket.send(gson.toJson(mapOf(
                                "type" to "stt", "text" to sttText, "session_id" to sessionId
                            )))
                            webSocket.send(gson.toJson(mapOf(
                                "type" to "tts", "state" to "start", "session_id" to sessionId
                            )))
                            webSocket.send(gson.toJson(mapOf(
                                "type" to "tts", "state" to "sentence_start",
                                "text" to "好的，我听到了：$sttText", "session_id" to sessionId
                            )))
                            webSocket.send(gson.toJson(mapOf(
                                "type" to "tts", "state" to "stop", "session_id" to sessionId
                            )))
                        }
                    }
                }
            } finally {
                latch.countDown()
            }
        }.apply { isDaemon = true }.start()
    }

    /** 按指纹匹配预设文本；未命中时使用默认响应 */
    private fun resolveStt(fingerprint: AudioFingerprint): String? {
        synchronized(sttMapping) {
            for ((key, value) in sttMapping) {
                if (key.matches(fingerprint)) return value
            }
        }
        return defaultSttResponse
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }
}
