package org.oxff.helloxiaozhi.activation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.oxff.helloxiaozhi.config.AppConfig
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
 * 3. activation 字段消失即激活完成 → 回调 onReady 建立 WebSocket 连接。
 *
 * 注意：官方协议要求激活完成后必须新建 WebSocket 连接才生效。
 */
class ActivationFlow(
    private val otaClient: OtaClient,
    private val config: AppConfig,
) {

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
    fun ensureActivated(listener: Listener) {
        if (running) return
        running = true
        checkNowRequested = false
        scope.launch {
            try {
                var response = otaClient.register(config, DeviceInfoProvider.localIp())
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
                            response = otaClient.register(config, DeviceInfoProvider.localIp())
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

    private companion object {
        const val POLL_INTERVAL_MS = 5000L
        const val POLL_TICK_MS = 1000L
    }
}
