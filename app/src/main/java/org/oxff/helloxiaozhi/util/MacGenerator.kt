package org.oxff.helloxiaozhi.util

import java.util.Locale
import kotlin.random.Random

/**
 * MAC 地址生成与校验，对应设计稿 add-bot-modal.js 的 genMac()。
 *
 * 格式与 [DeviceInfoProvider.realMacAddress] 保持一致（小写、冒号分隔），
 * 因为机器人的 MAC 会直接作为 WebSocket 握手的 Device-Id 与 OTA 注册的
 * mac_address 上行，两条来源必须同形。
 */
object MacGenerator {

    private val PATTERN = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")

    /** 生成随机 MAC（小写、冒号分隔） */
    fun random(random: Random = Random.Default): String =
        (0 until 6).joinToString(":") {
            String.format(Locale.US, "%02x", random.nextInt(256))
        }

    fun isValid(mac: String): Boolean = PATTERN.matches(mac.trim())

    /** 归一化为小写冒号分隔形式；非法输入原样 trim 返回，由调用方先行校验 */
    fun normalize(mac: String): String = mac.trim().lowercase(Locale.US)
}
