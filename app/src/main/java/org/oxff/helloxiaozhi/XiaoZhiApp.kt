package org.oxff.helloxiaozhi

import android.app.Application
import org.oxff.helloxiaozhi.controller.XiaoZhiController

/**
 * 应用入口：持有全局唯一的 XiaoZhiController 单例，
 * MainActivity / VoiceCallActivity / SettingsActivity 共享连接与通话状态。
 */
class XiaoZhiApp : Application() {

    lateinit var controller: XiaoZhiController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = XiaoZhiController(this)
    }
}
