package org.oxff.helloxiaozhi.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.oxff.helloxiaozhi.chat.HelloMessage
import org.oxff.helloxiaozhi.config.AppConfig

/**
 * WebSocket 长连接客户端，对应：
 *  - Web 端 WebSocketManager.ts（连接生命周期、hello 握手、重连）
 *  - ref 后端 websocket_proxy.py 的服务器侧握手（Device-Id / Client-Id /
 *    Protocol-Version / Authorization 请求头）
 *
 * 消息分两类：
 *  - 文本帧：JSON 消息（hello/stt/llm/tts/listen...）
 *  - 二进制帧：Opus 音频
 *
 * 注意：回调发生在 OkHttp 工作线程，UI 相关逻辑由上层（XiaoZhiController）
 * 自行切换线程。
 */
class XiaoZhiWebSocket(
    private val okHttpClient: OkHttpClient,
    private val config: AppConfig,
    private val gson: Gson,
    private val listener: Listener,
) {

    interface Listener {
        /** 连接建立并发送 hello 后回调 */
        fun onConnected()

        /** 连接关闭 */
        fun onDisconnected()

        /** 连接失败/异常（message 为可展示的错误文案） */
        fun onError(message: String)

        /** 收到文本帧（JSON 原始字符串） */
        fun onTextMessage(text: String)

        /** 收到二进制帧（Opus 音频数据） */
        fun onAudioFrame(opusData: ByteArray)
    }

    private var webSocket: WebSocket? = null

    /**
     * 本次连接（含其自动重连）所用的设备身份。
     *
     * 在 [connect] 时钉住，而不是每次从 config 实时读：每个机器人是独立设备身份，
     * 切换机器人时若排队中的重连任务读到了新 MAC，会用错误身份重连。
     */
    @Volatile
    private var deviceId: String = ""

    /** 断开后是否自动重连（disconnect() 时置 false，对应 Web 端 disconnect） */
    @Volatile
    private var autoReconnect = false

    @Volatile
    private var reconnectScheduled = false

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connectInternal() }

    /** 建立连接；已连接时为空操作 */
    fun connect(deviceId: String) {
        this.deviceId = deviceId
        connectInternal()
    }

    private fun connectInternal() {
        if (webSocket != null) return
        // 本次连接（无论主动或由重连任务触发）接管后，清除遗留的重连任务
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false

        val builder = Request.Builder()
            .url(config.wsUrl)
            .header("Device-Id", deviceId)
            .header("Client-Id", config.clientId)
            .header("Protocol-Version", "1")
        if (config.tokenEnable) {
            builder.header("Authorization", "Bearer ${config.token}")
        }

        autoReconnect = true
        webSocket = okHttpClient.newWebSocket(builder.build(), wsListener)
    }

    /** 主动断开并停止重连 */
    fun disconnect() {
        autoReconnect = false
        reconnectHandler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    val isConnected: Boolean get() = webSocket != null

    /** 发送 JSON 文本消息 */
    fun sendText(message: Any) {
        val ws = webSocket ?: return
        val json = if (message is String) message else gson.toJson(message)
        ws.send(json)
    }

    /** 发送 Opus 二进制帧 */
    fun sendOpus(data: ByteArray) {
        if (data.isEmpty()) return
        webSocket?.send(ByteString.of(*data))
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || reconnectScheduled) return
        reconnectScheduled = true
        // 3 秒后重连（与 Web 端 App.vue onDisconnect 保持一致）
        reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private val wsListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS onOpen, send hello")
            // 连接建立后立即发送 hello 握手消息
            sendText(HelloMessage())
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            listener.onTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size > 0) {
                listener.onAudioFrame(bytes.toByteArray())
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WS onClosed: code=$code reason=$reason")
            this@XiaoZhiWebSocket.webSocket = null
            listener.onDisconnected()
            scheduleReconnect()
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            Log.e(TAG, "WS onFailure: ${t.message}, response=${response?.code}")
            this@XiaoZhiWebSocket.webSocket = null
            listener.onError(t.message ?: "连接失败")
            scheduleReconnect()
        }
    }

    private companion object {
        const val TAG = "XiaoZhiWebSocket"
        const val RECONNECT_DELAY_MS = 3000L
    }
}
