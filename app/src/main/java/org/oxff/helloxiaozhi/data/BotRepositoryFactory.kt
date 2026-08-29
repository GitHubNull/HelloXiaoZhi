package org.oxff.helloxiaozhi.data

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * [BotRepository] 的构造入口：把 Context 相关的路径与首启 seed 组装好，
 * 仓库本体只依赖 File，从而无需 Robolectric 即可单测。
 */
object BotRepositoryFactory {

    private const val FILE_NAME = "app_data.json"

    /**
     * @param deviceMac 本机物理 MAC（AppConfig.deviceId），作为默认机器人的身份
     */
    fun create(context: Context, gson: Gson, deviceMac: String): BotRepository =
        BotRepository(
            file = File(context.applicationContext.filesDir, FILE_NAME),
            gson = gson,
            seedFactory = { seed(deviceMac) },
        )

    /**
     * 首启 / 重置后的初始状态：**只有一个**机器人，MAC 取本机物理 MAC。
     *
     * 设计稿 mock 预置了小智/小学/小娱/小科四个机器人和三段示例对话
     * （mock.js XZ_DEFAULT_BOTS / XZ_DEFAULT_CHATS），那是原型填充物。在真实实现里
     * 每个机器人都是一个需要单独在小智控制台绑定的设备身份，凭空预置三个未激活的
     * MAC 与三段伪造聊天记录会误导用户，因此只保留默认的小智，会话为空——聊天 Tab
     * 直接呈现设计稿已画好的空状态引导用户去通讯录。
     *
     * 不预置问候语：真实问候由服务端在连接后下发。
     */
    fun seed(deviceMac: String): AppData = AppData(
        bots = mutableListOf(
            Bot(
                id = DEFAULT_BOT_ID,
                name = "小智",
                avatarText = "智",
                avatarColorIndex = 0,
                tags = listOf("默认"),
                desc = "小智 AI 助手",
                mac = deviceMac,
                activated = false,
                createdAt = System.currentTimeMillis(),
            ),
        ),
        wakeTargetBotId = DEFAULT_BOT_ID,
        activeBotId = DEFAULT_BOT_ID,
    )

    const val DEFAULT_BOT_ID = "bot_default"
}
