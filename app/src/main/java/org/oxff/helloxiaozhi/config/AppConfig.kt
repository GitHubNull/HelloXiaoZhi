package org.oxff.helloxiaozhi.config

import android.content.Context
import java.util.UUID

/**
 * 应用配置持久化（SharedPreferences）。
 *
 * 默认值与 ref 后端 config.py 的 _default_config 保持一致：
 *  - ws_url: 官方 WebSocket 地址
 *  - ota_url: 官方 OTA 注册地址
 *  - token_enable: true
 *  - token: test_token
 */
class AppConfig(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "xiaozhi_config"
        const val DEFAULT_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/"
        const val DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"

        private const val KEY_WS_URL = "ws_url"
        private const val KEY_OTA_URL = "ota_url"
        private const val KEY_TOKEN_ENABLE = "token_enable"
        private const val KEY_TOKEN = "token"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_DEVICE_ID = "device_id"
    }

    /** WebSocket 服务器地址（官方或自建代理） */
    var wsUrl: String
        get() = sp.getString(KEY_WS_URL, DEFAULT_WS_URL) ?: DEFAULT_WS_URL
        set(value) = sp.edit().putString(KEY_WS_URL, value).apply()

    /** OTA 注册接口地址 */
    var otaUrl: String
        get() = sp.getString(KEY_OTA_URL, DEFAULT_OTA_URL) ?: DEFAULT_OTA_URL
        set(value) = sp.edit().putString(KEY_OTA_URL, value).apply()

    /** 是否在 WebSocket 握手时携带 Authorization 头 */
    var tokenEnable: Boolean
        get() = sp.getBoolean(KEY_TOKEN_ENABLE, true)
        set(value) = sp.edit().putBoolean(KEY_TOKEN_ENABLE, value).apply()

    /** Bearer Token 值 */
    var token: String
        get() = sp.getString(KEY_TOKEN, "test_token") ?: "test_token"
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    /** 客户端唯一 ID（UUID，首次访问生成后持久化，对应 config.py 的 CLIENT_ID） */
    val clientId: String
        get() {
            sp.getString(KEY_CLIENT_ID, null)?.let { return it }
            val newId = UUID.randomUUID().toString()
            sp.edit().putString(KEY_CLIENT_ID, newId).apply()
            return newId
        }

    /**
     * 本机物理 MAC（对应 config.py 的 DEVICE_ID），由 DeviceInfoProvider 生成后写入。
     *
     * 注意语义：它是**默认机器人的身份**与新建机器人的 MAC 初始值，
     * 并非「当前在线的 Device-Id」——握手实际使用的是当前激活机器人的 MAC，
     * 见 XiaoZhiController.switchActiveBot 与 XiaoZhiWebSocket.connect(deviceId)。
     */
    var deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, null) ?: ""
        set(value) = sp.edit().putString(KEY_DEVICE_ID, value).apply()

    /** 是否为官方服务器直连模式（决定是否执行 OTA 注册与验证码激活流程） */
    fun isOfficialMode(): Boolean =
        wsUrl.trimEnd('/') == DEFAULT_WS_URL.trimEnd('/')

    /**
     * 清空全部配置，回到默认值（设置页「重置应用数据」）。
     *
     * clientId 与 deviceId 一并清除：两者都会在下次访问时重新生成，
     * 从而真正回到「首次安装」状态。
     */
    fun clear() = sp.edit().clear().apply()
}
