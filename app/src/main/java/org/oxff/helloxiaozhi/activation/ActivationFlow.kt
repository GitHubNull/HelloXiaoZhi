package org.oxff.helloxiaozhi.activation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.oxff.helloxiaozhi.net.OtaClient
import org.oxff.helloxiaozhi.net.OtaException
import org.oxff.helloxiaozhi.util.DeviceInfoProvider

/**
 * 官方服务器的设备激活（验证码）流程编排。
 *
 * 对应官方固件 kDeviceStateActivating → kDeviceStateIdle 的激活阶段：
 * 1. POST OTA 注册请求；
 * 2. 响应含 activation.code → 通知 UI 展示验证码，并每 5 秒轮询一次
 *    （用户也可手动触发立即检查，对应"我已添加设备"按钮）；
 * 3. activation 字段消失即激活完成 → 回调 onActivated 建立 WebSocket 连接。
 *
 * 注意：官方协议要求激活完成后必须新建 WebSocket 连接才生效。
 *
 * 身份不再持有 AppConfig：每个机器人是独立设备身份，[Identity] 由调用方按
 * 当前激活的机器人传入，「获取该 MAC 的激活码」也复用同一套注册逻辑。
 */
class ActivationFlow(
    private val otaClient: OtaClient,
) {

    /** 一次 OTA 注册所用的身份：OTA 地址 + 设备 MAC + 客户端 UUID */
    data class Identity(
        val otaUrl: String,
        val deviceId: String,
        val clientId: String,
    )

    /** 回调均发生在主线程 */
    class Listener {
        /** 需要用户到 xiaozhi.me 输入验证码（code 为 6 位验证码） */
        var onCodeRequired: ((String) -> Unit)? = null

        /** 激活完成，可建立 WebSocket 连接（code 为最后一次检查的验证码，可能为空） */
        var onActivated: ((code: String?) -> Unit)? = null

        /** OTA 注册失败（message 为可展示的错误文案） */
        var onError: ((String) -> Unit)? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var running = false

    @Volatile
    private var checkNowRequested = false

    /**
     * 执行激活检查；完成（或用户取消）后回调对应事件。
     * 同时只会有一个检查流程在跑。
     */
    fun ensureActivated(identity: Identity, listener: Listener) {
        if (running) return
        running = true
        checkNowRequested = false
        scope.launch {
            try {
                var response = register(identity)
                var code = response.activationCode
                if (!code.isNullOrBlank()) {
                    listener.onCodeRequired?.invoke(code)
                    var lastCheck = System.currentTimeMillis()
                    while (running) {
                        if (checkNowRequested ||
                            System.currentTimeMillis() - lastCheck >= POLL_INTERVAL_MS
                        ) {
                            checkNowRequested = false
                            lastCheck = System.currentTimeMillis()
                            response = register(identity)
                            code = response.activationCode
                            if (code.isNullOrBlank()) break
                        }
                        delay(POLL_TICK_MS)
                    }
                    if (!running) return@launch
                    listener.onActivated?.invoke(code)
                } else {
                    listener.onActivated?.invoke(code)
                }
            } catch (e: OtaException) {
                if (running) listener.onError?.invoke(e.message ?: "OTA 注册失败")
            } catch (e: Exception) {
                if (running) listener.onError?.invoke("OTA 注册失败: ${e.message}")
            } finally {
                running = false
                checkNowRequested = false
            }
        }
    }

    /**
     * 单次注册探测：返回该 MAC 的激活码，已绑定则返回 null。
     *
     * 供「获取该 MAC 的激活码」使用：为任意 MAC 发起一次注册，不轮询、
     * 不占用 [running] 标志（不会与进行中的 [ensureActivated] 互相干扰）、
     * 也不触碰全局配置。
     *
     * @throws OtaException 请求失败
     */
    suspend fun probeOnce(identity: Identity): String? =
        register(identity).activationCode

    /** 用户点击"我已添加设备"时立即触发一次检查 */
    fun requestCheckNow() {
        checkNowRequested = true
    }

    /** 用户取消激活（关闭对话框/退出设置） */
    fun cancel() {
        running = false
        checkNowRequested = false
    }

    /** 释放协程资源（应用退出时调用） */
    fun shutdown() {
        cancel()
        scope.cancel()
    }

    private suspend fun register(identity: Identity) = otaClient.register(
        otaUrl = identity.otaUrl,
        deviceId = identity.deviceId,
        clientId = identity.clientId,
        localIp = DeviceInfoProvider.localIp(),
    )

    private companion object {
        const val POLL_INTERVAL_MS = 5000L
        const val POLL_TICK_MS = 1000L
    }
}
