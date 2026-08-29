package org.oxff.helloxiaozhi

import android.app.Application
import com.google.gson.Gson
import org.oxff.helloxiaozhi.config.AppConfig
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.data.BotRepositoryFactory
import org.oxff.helloxiaozhi.util.DeviceInfoProvider

/**
 * 应用入口：持有全局唯一的配置、数据仓库与 XiaoZhiController，
 * 三 Tab 外壳与通话页共享同一份连接与通话状态。
 *
 * 装配顺序是有讲究的：设备 MAC 必须先落地，仓库的首启 seed 才能拿它作为
 * 默认机器人的身份；控制器再依赖这两者，因此三者不能颠倒。
 */
class XiaoZhiApp : Application() {

    lateinit var config: AppConfig
        private set

    lateinit var repository: BotRepository
        private set

    lateinit var controller: XiaoZhiController
        private set

    override fun onCreate() {
        super.onCreate()
        val gson = Gson()
        config = AppConfig(this)
        // 首次启动生成本机 MAC（对应 ref config.py 的 DEVICE_ID）
        if (config.deviceId.isBlank()) {
            config.deviceId = DeviceInfoProvider.obtainDeviceId(this)
        }
        repository = BotRepositoryFactory.create(this, gson, config.deviceId)
        controller = XiaoZhiController(this, config, gson, repository)
    }
}
