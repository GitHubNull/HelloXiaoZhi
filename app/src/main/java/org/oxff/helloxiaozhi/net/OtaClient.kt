package org.oxff.helloxiaozhi.net

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.oxff.helloxiaozhi.config.AppConfig
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * OTA 设备注册客户端，对应 ref 后端 websocket_proxy.py 的 _update_ota_address()。
 *
 * 官方服务器交互：
 * 1. POST {ota_url}，请求头携带 Device-Id，JSON 固件信息 payload；
 * 2. 未注册设备响应中包含 activation 字段（code 为验证码），
 *    用户在 xiaozhi.me 控制台输入验证码完成激活后，activation 字段消失。
 */
class OtaClient(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) {

    /**
     * 发起 OTA 注册请求。
     *
     * @throws OtaException 请求失败/超时（错误文案与 ref 后端一致）
     */
    suspend fun register(config: AppConfig, localIp: String): OtaResponse =
        withContext(Dispatchers.IO) {
            val payload = buildPayload(config.deviceId, config.clientId, localIp)
            val request = Request.Builder()
                .url(config.otaUrl)
                .header("Device-Id", config.deviceId)
                .header("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw OtaException("OTA 服务器错误: HTTP ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    OtaResponse.fromJson(gson, body)
                }
            } catch (e: SocketTimeoutException) {
                throw OtaException("OTA 请求超时，请稍后重试")
            } catch (e: IOException) {
                throw OtaException("无法连接到 OTA 服务器，请检查网络连接")
            }
        }

    /**
     * 构建 OTA 请求 payload。
     * 逐字段对齐 websocket_proxy.py 第 59-80 行，保证官方服务器校验通过。
     */
    fun buildPayload(deviceId: String, clientId: String, localIp: String): Map<String, Any?> = mapOf(
        "version" to 2,
        "flash_size" to 16777216,
        "psram_size" to 0,
        "minimum_free_heap_size" to 8318916,
        "mac_address" to deviceId,
        "uuid" to clientId,
        "chip_model_name" to "esp32s3",
        "chip_info" to mapOf(
            "model" to 9,
            "cores" to 2,
            "revision" to 2,
            "features" to 18,
        ),
        "application" to mapOf(
            "name" to "xiaozhi",
            "version" to "1.1.2",
            "idf_version" to "v5.3.2-dirty",
        ),
        "partition_table" to emptyList<Any>(),
        "ota" to mapOf("label" to "factory"),
        "board" to mapOf(
            "type" to "bread-compact-wifi",
            "ip" to localIp,
            "mac" to deviceId,
        ),
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/** OTA 异常（携带可直接展示给用户的错误文案） */
class OtaException(message: String) : Exception(message)

/** OTA 响应（只关注 activation 字段，与 ref 后端一致） */
data class OtaResponse(
    val activation: Activation? = null,
) {
    /** 设备是否需要激活（存在 activation 且含验证码） */
    val activationCode: String?
        get() = activation?.code?.takeIf { it.isNotBlank() }

    companion object {
        fun fromJson(gson: Gson, text: String): OtaResponse = try {
            gson.fromJson(text, OtaResponse::class.java)
        } catch (_: Exception) {
            OtaResponse()
        }
    }
}

/** activation 字段结构（code = 6 位验证码） */
data class Activation(
    val code: String? = null,
    val message: String? = null,
    val challenge: String? = null,
)
