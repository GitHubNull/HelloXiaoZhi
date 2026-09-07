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
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
        private const val KEY_WAKE_SOUND_ENABLED = "wake_sound_enabled"
        private const val KEY_AI_DONE_SOUND_ENABLED = "ai_done_sound_enabled"
        private const val KEY_ROBOT_ACTION_ENABLED = "robot_action_enabled"
        private const val KEY_MUSIC_ENABLED = "music_enabled"
        private const val KEY_MUSIC_LOCAL_PATH = "music_local_path"
        private const val KEY_MUSIC_SAF_URI = "music_saf_uri"
        private const val KEY_MUSIC_CACHE_VERSION = "music_cache_version"
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

    /** 是否启用常驻唤醒词检测 */
    var wakeWordEnabled: Boolean
        get() = sp.getBoolean(KEY_WAKE_WORD_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    /** 唤醒词检测灵敏度（0.0 ~ 1.0，默认 0.5） */
    var wakeWordSensitivity: Float
        get() = sp.getFloat(KEY_WAKE_WORD_SENSITIVITY, 0.5f)
        set(value) = sp.edit().putFloat(KEY_WAKE_WORD_SENSITIVITY, value).apply()

    /** 唤醒成功时是否播放提示音（默认开启） */
    var wakeSoundEnabled: Boolean
        get() = sp.getBoolean(KEY_WAKE_SOUND_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_WAKE_SOUND_ENABLED, value).apply()

    /** AI 回答结束时是否播放提示音（默认开启） */
    var aiDoneSoundEnabled: Boolean
        get() = sp.getBoolean(KEY_AI_DONE_SOUND_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_AI_DONE_SOUND_ENABLED, value).apply()

    /** 是否启用机器人动作与表情（默认开启，仅在 Visbot 设备上生效） */
    var robotActionEnabled: Boolean
        get() = sp.getBoolean(KEY_ROBOT_ACTION_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ROBOT_ACTION_ENABLED, value).apply()

    /** 是否启用音乐播放功能（默认关闭） */
    var musicEnabled: Boolean
        get() = sp.getBoolean(KEY_MUSIC_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_MUSIC_ENABLED, value).apply()

    /** 本地音乐目录路径（空表示未配置） */
    var musicLocalPath: String
        get() = sp.getString(KEY_MUSIC_LOCAL_PATH, "") ?: ""
        set(value) = sp.edit().putString(KEY_MUSIC_LOCAL_PATH, value).apply()

    /** SAF 文档树 URI（空表示未配置） */
    var musicSafUri: String
        get() = sp.getString(KEY_MUSIC_SAF_URI, "") ?: ""
        set(value) = sp.edit().putString(KEY_MUSIC_SAF_URI, value).apply()

    /** 音乐缓存版本号（用于缓存失效判断） */
    var musicCacheVersion: Int
        get() = sp.getInt(KEY_MUSIC_CACHE_VERSION, 0)
        set(value) = sp.edit().putInt(KEY_MUSIC_CACHE_VERSION, value).apply()

    /**
     * 清空全部配置，回到默认值（设置页「重置应用数据」）。
     *
     * clientId 与 deviceId 一并清除：两者都会在下次访问时重新生成，
     * 从而真正回到「首次安装」状态。
     */
    fun clear() = sp.edit().clear().apply()
}
