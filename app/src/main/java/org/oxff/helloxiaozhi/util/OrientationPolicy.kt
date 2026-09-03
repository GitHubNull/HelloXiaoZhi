package org.oxff.helloxiaozhi.util

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build

/**
 * 小屏面板方向策略。
 *
 * 机器人脸屏类真机（如 alps Visbot 308x240@120dpi）物理面板原生横屏，但厂商 ROM
 * 会在第三方 Activity 启动时强制打开加速度计旋转，传感器按机身摆放把应用锁成竖屏，
 * 系统设置层的 user_rotation 会被厂商服务回滚，扛不住。
 *
 * 对策：仅对「原生横屏 + 小屏（sw < 360dp）」设备在 Activity 层显式请求横屏——
 * 显式 requestedOrientation 优先于传感器策略；普通手机（原生竖屏）与
 * 平板（sw >= 360dp）不受影响，仍跟随系统旋转。
 *
 * 原生方向判定：API 23+ 优先用 `Display.getMode()` 的物理面板尺寸（physicalWidth/Height），
 * 绕开厂商 ROM 把 natural orientation 上报为竖屏、导致 `Display.getRotation()` 启发式误判的问题；
 * API 21-22 回落到 rotation 启发式。
 */
object OrientationPolicy {

    private const val SMALL_SCREEN_SW_DP = 360

    /**
     * 仅对「原生横屏 + 小屏」设备在 onCreate 最前面显式请求横屏。
     * 读取 display 信息在极个别 ROM 上可能抛异常，失败时静默跳过（保持系统默认行为）。
     */
    fun lockIfNeeded(activity: Activity) {
        if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) return
        val nativeLandscape = try {
            val display = activity.windowManager.defaultDisplay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // API 23+：直接读物理面板尺寸，不受 ROM 篡改 rotation/natural orientation 影响
                val mode = display.mode
                mode.physicalWidth > mode.physicalHeight
            } else {
                // API 21-22 fallback：rotation 启发式
                val config = activity.resources.configuration
                val rotation = display.rotation
                val currentLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
                // 奇数旋转意味着当前方向与原生方向相反，据此还原原生面板方向
                if (rotation % 2 == 1) !currentLandscape else currentLandscape
            }
        } catch (_: Exception) {
            return
        }
        if (nativeLandscape &&
            activity.resources.configuration.smallestScreenWidthDp < SMALL_SCREEN_SW_DP
        ) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
}
