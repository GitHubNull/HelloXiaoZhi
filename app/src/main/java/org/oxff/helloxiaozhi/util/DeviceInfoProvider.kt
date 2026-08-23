package org.oxff.helloxiaozhi.util

import android.content.Context
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID

/**
 * 设备标识信息提供者，对应 ref 后端 utils/device.py。
 *
 * - MAC 地址（DEVICE_ID）：优先取真实网卡硬件地址；
 *   Android 10+ 或目标设备拿不到时，用 ANDROID_ID 派生稳定的伪 MAC（与 ref
 *   get_mac_address 用 uuid.getnode() 派生伪 MAC 的思路一致）。
 * - 客户端 ID（CLIENT_ID）：随机 UUID，由 AppConfig 生成并持久化。
 * - 本机 IP：遍历网卡取第一个非回环 IPv4（对应 get_local_ip）。
 */
object DeviceInfoProvider {

    /**
     * 获取设备 MAC 格式唯一标识，优先尝试真实网卡地址。
     * 结果由调用方（AppConfig）持久化，保证跨启动稳定。
     */
    fun obtainDeviceId(context: Context): String {
        val real = realMacAddress()
        return real ?: pseudoMacFromAndroidId(context)
    }

    /** 尝试从 wlan/eth/p2p 网卡读取真实 MAC（xx:xx:xx:xx:xx:xx 小写格式） */
    fun realMacAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces) {
                if (ni.isLoopback || !ni.isUp) continue
                val name = ni.name.lowercase(Locale.US)
                if (!(name.startsWith("wlan") || name.startsWith("eth") ||
                            name.startsWith("p2p"))) continue
                val hw = ni.hardwareAddress ?: continue
                if (hw.size != 6) continue
                if (hw.all { it.toInt() == 0 }) continue
                return hw.joinToString(":") {
                    String.format(Locale.US, "%02x", it.toInt() and 0xFF)
                }
            }
        } catch (_: Exception) {
            // 无权限或枚举失败时静默降级
        }
        return null
    }

    /** 用 ANDROID_ID 派生稳定的伪 MAC（后 12 位 hex，按 aa:bb:cc:dd:ee:ff 格式化） */
    fun pseudoMacFromAndroidId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
        val hex = UUID.nameUUIDFromBytes(androidId.toByteArray())
            .toString()
            .replace("-", "")
            .takeLast(12)
        return hex.chunked(2).joinToString(":")
    }

    /** 获取本机局域网 IPv4 地址，失败时返回 127.0.0.1（对应 ref get_local_ip） */
    fun localIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return FALLBACK_IP
            for (ni in interfaces) {
                if (ni.isLoopback || !ni.isUp) continue
                for (addr in ni.inetAddresses) {
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    addr.hostAddress?.let { return it }
                }
            }
        } catch (_: Exception) {
            // 静默降级
        }
        return FALLBACK_IP
    }

    private const val FALLBACK_IP = "127.0.0.1"
}
