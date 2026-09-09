package org.oxff.helloxiaozhi.controller

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.oxff.helloxiaozhi.activation.ActivationFlow
import org.oxff.helloxiaozhi.chat.ConnectionStatus
import org.oxff.helloxiaozhi.config.AppConfig
import org.oxff.helloxiaozhi.data.Bot
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.net.XiaoZhiWebSocket

/**
 * 连接管理器：负责 WebSocket 连接生命周期与激活流程协调。
 *
 * 职责：
 *  - 管理 WebSocket 连接（建立、断开、重连）
 *  - 协调官方模式的激活流程（OTA 注册、验证码激活）
 *  - 管理当前激活的机器人身份
 *  - 处理连接状态变更通知
 *
 * 从 XiaoZhiController 拆分而来，专注于连接管理职责。
 */
class ConnectionManager(
    private val config: AppConfig,
    private val repository: BotRepository,
    private val ws: XiaoZhiWebSocket,
    private val activationFlow: ActivationFlow,
    private val mainHandler: Handler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** 连接状态变更回调 */
    var onConnectionStatusChanged: ((ConnectionStatus) -> Unit)? = null

    /** 需要激活码回调（官方模式） */
    var onActivationCodeRequired: ((String) -> Unit)? = null

    /** 激活完成回调 */
    var onActivationCompleted: (() -> Unit)? = null

    /** 错误回调 */
    var onError: ((String) -> Unit)? = null

    /** 当前连接状态 */
    @Volatile
    var connectionStatus = ConnectionStatus.DISCONNECTED
        private set

    /** 当前激活的机器人 ID */
    @Volatile
    var activeBotId: String? = null
        private set

    /** 当前会话 ID */
    @Volatile
    var sessionId = ""
        private set

    init {
        activeBotId = repository.defaultBot()?.id
        activeBotId?.let { repository.activeBotId = it }
    }

    /**
     * 确保 WebSocket 已连接（官方模式先走激活流程）
     */
    fun ensureConnected() {
        if (connectionStatus == ConnectionStatus.CONNECTED ||
            connectionStatus == ConnectionStatus.CONNECTING
        ) return
        val bot = repository.bot(activeBotId) ?: return
        setConnectionStatus(ConnectionStatus.CONNECTING)
        if (config.isOfficialMode()) {
            activationFlow.ensureActivated(identityFor(bot), activationListener)
        } else {
            ws.connect(bot.mac)
        }
    }

    /**
     * 用户点击"我已添加设备"后立即检查一次激活状态
     */
    fun activationCheckNow() = activationFlow.requestCheckNow()

    /**
     * 用户取消激活（关闭对话框）
     */
    fun cancelActivation() = activationFlow.cancel()

    /**
     * 设置变更后应用：断开重连（下次 ensureConnected 生效）
     */
    fun applySettings() {
        activationFlow.cancel()
        ws.disconnect()
        setConnectionStatus(ConnectionStatus.DISCONNECTED)
    }

    /**
     * 切换当前激活的机器人。
     *
     * 顺序是强制的：先取消激活轮询、再断开（disconnect 是唯一清除
     * autoReconnect 与待执行重连任务的地方）、然后才提交新身份并触发重连。
     * 否则排队中的重连任务会用切换后的 MAC 重连，造成身份错乱。
     */
    fun switchActiveBot(botId: String) {
        if (activeBotId == botId && connectionStatus == ConnectionStatus.CONNECTED) return
        val bot = repository.bot(botId) ?: return
        activationFlow.cancel()
        ws.disconnect()
        sessionId = ""
        setConnectionStatus(ConnectionStatus.DISCONNECTED)
        activeBotId = botId
        repository.activeBotId = botId
        ensureConnected()
    }

    /**
     * 当前激活机器人是否已完成绑定（OTA 不再返回 activation 字段）
     */
    fun isActiveBotActivated(): Boolean = repository.bot(activeBotId)?.activated == true

    /**
     * 处理 WebSocket 连接成功
     */
    fun onWebSocketConnected() {
        mainHandler.post { setConnectionStatus(ConnectionStatus.CONNECTED) }
    }

    /**
     * 处理 WebSocket 断开
     */
    fun onWebSocketDisconnected() {
        mainHandler.post {
            sessionId = ""
            setConnectionStatus(ConnectionStatus.DISCONNECTED)
        }
    }

    /**
     * 处理 WebSocket 错误
     */
    fun onWebSocketError(message: String) {
        mainHandler.post {
            // 错误状态下也要清空 sessionId，因为连接已经断开
            sessionId = ""
            setConnectionStatus(ConnectionStatus.ERROR)
            onError?.invoke(message)
        }
    }

    /**
     * 处理 Hello 消息（记录 session_id）
     */
    fun onHelloReceived(sessionId: String) {
        this.sessionId = sessionId
    }

    /**
     * 为任意 MAC 请求一次激活码（不轮询、不改全局配置、不占用激活流程）
     */
    fun requestActivationCodeFor(mac: String, callback: (code: String?, error: String?) -> Unit) {
        scope.launch {
            try {
                val code = activationFlow.probeOnce(
                    ActivationFlow.Identity(config.otaUrl, mac, config.clientId),
                )
                callback(code, null)
            } catch (e: Exception) {
                callback(null, e.message ?: "请求失败")
            }
        }
    }

    private fun identityFor(bot: Bot) = ActivationFlow.Identity(
        otaUrl = config.otaUrl,
        deviceId = bot.mac,
        clientId = config.clientId,
    )

    private val activationListener = ActivationFlow.Listener().apply {
        onCodeRequired = { code ->
            mainHandler.post { onActivationCodeRequired?.invoke(code) }
        }
        onActivated = { code ->
            mainHandler.post {
                repository.bot(activeBotId)?.let { repository.markActivated(it.id, true) }
                onActivationCompleted?.invoke()
                // 激活完成后必须新建 WebSocket 连接（官方协议要求）
                repository.bot(activeBotId)?.let { ws.connect(it.mac) }
            }
        }
        onError = { message ->
            mainHandler.post {
                setConnectionStatus(ConnectionStatus.ERROR)
                onError?.invoke(message)
            }
        }
    }

    private fun setConnectionStatus(status: ConnectionStatus) {
        if (connectionStatus == status) return
        connectionStatus = status
        onConnectionStatusChanged?.invoke(status)
    }
}
