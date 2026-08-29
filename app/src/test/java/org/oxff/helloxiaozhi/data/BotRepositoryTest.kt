package org.oxff.helloxiaozhi.data

import com.google.gson.Gson
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.oxff.helloxiaozhi.chat.ChatRole

/**
 * 机器人与会话仓库单元测试。
 *
 * 仓库故意只依赖 File 而非 Context，因此这些用例是纯 JVM 的，无需 Robolectric。
 */
class BotRepositoryTest {

    private lateinit var dir: File
    private lateinit var file: File
    private val gson = Gson()

    private val deviceMac = "aa:bb:cc:dd:ee:ff"

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "xz-repo-test-${System.nanoTime()}")
        dir.mkdirs()
        file = File(dir, "app_data.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newRepo(): BotRepository =
        BotRepository(file, gson) { BotRepositoryFactory.seed(deviceMac) }

    // ---------------- seed ----------------

    @Test
    fun `首启 seed 只有一个机器人 且 MAC 等于本机 MAC`() {
        val repo = newRepo()

        val bots = repo.bots()
        assertEquals(1, bots.size)
        assertEquals(BotRepositoryFactory.DEFAULT_BOT_ID, bots[0].id)
        assertEquals(deviceMac, bots[0].mac)
        // 未绑定，连接时应触发激活流程
        assertFalse(bots[0].activated)
    }

    @Test
    fun `首启没有任何会话 聊天页应呈现空状态`() {
        val repo = newRepo()

        assertTrue(repo.conversations().isEmpty())
        assertEquals(0, repo.totalUnread())
    }

    @Test
    fun `首启唤醒目标与当前机器人都指向默认机器人`() {
        val repo = newRepo()

        assertEquals(BotRepositoryFactory.DEFAULT_BOT_ID, repo.wakeTargetBotId)
        assertEquals(BotRepositoryFactory.DEFAULT_BOT_ID, repo.activeBotId)
        assertEquals(BotRepositoryFactory.DEFAULT_BOT_ID, repo.defaultBot()?.id)
    }

    // ---------------- 追加消息 ----------------

    @Test
    fun `追加 AI 消息累加未读并更新预览与时间`() {
        val repo = newRepo()
        val botId = BotRepositoryFactory.DEFAULT_BOT_ID

        repo.appendMessage(botId, ChatRole.AI, "你好", ts = 1000L)
        repo.appendMessage(botId, ChatRole.AI, "在的", ts = 2000L)

        val conversation = repo.conversation(botId)!!
        assertEquals(2, conversation.unread)
        assertEquals("在的", conversation.lastPreview)
        assertEquals(2000L, conversation.lastTs)
        assertEquals(2, repo.totalUnread())
    }

    @Test
    fun `用户消息不累加未读`() {
        val repo = newRepo()
        val botId = BotRepositoryFactory.DEFAULT_BOT_ID

        repo.appendMessage(botId, ChatRole.USER, "查天气", ts = 1000L)

        assertEquals(0, repo.conversation(botId)!!.unread)
        assertEquals("查天气", repo.conversation(botId)!!.lastPreview)
    }

    @Test
    fun `清除未读后计数归零`() {
        val repo = newRepo()
        val botId = BotRepositoryFactory.DEFAULT_BOT_ID
        repo.appendMessage(botId, ChatRole.AI, "你好", ts = 1000L)

        repo.clearUnread(botId)

        assertEquals(0, repo.totalUnread())
    }

    @Test
    fun `正在查看的对话到达 AI 消息不计未读 但预览与时间仍更新`() {
        val repo = newRepo()
        val botId = BotRepositoryFactory.DEFAULT_BOT_ID
        repo.visibleBotId = botId

        repo.appendMessage(botId, ChatRole.AI, "回答一", ts = 1000L)
        repo.appendMessage(botId, ChatRole.AI, "回答二", ts = 2000L)

        val conversation = repo.conversation(botId)!!
        assertEquals(0, conversation.unread)
        assertEquals(0, repo.totalUnread())
        assertEquals("回答二", conversation.lastPreview)
        assertEquals(2000L, conversation.lastTs)
        // 消息本身仍然落库（详情页实时展示不受影响）
        assertEquals(2, repo.messages(botId).size)
    }

    @Test
    fun `退出查看后 AI 消息恢复累加未读`() {
        val repo = newRepo()
        val botId = BotRepositoryFactory.DEFAULT_BOT_ID
        repo.visibleBotId = botId
        repo.appendMessage(botId, ChatRole.AI, "查看中", ts = 1000L)

        repo.visibleBotId = null
        repo.appendMessage(botId, ChatRole.AI, "已离开", ts = 2000L)

        assertEquals(1, repo.conversation(botId)!!.unread)
        assertEquals(1, repo.totalUnread())
    }

    @Test
    fun `查看状态不影响其它机器人的未读累加`() {
        val repo = newRepo()
        repo.addBot(bot("bot_b", "小学", "11:22:33:44:55:66"))
        repo.visibleBotId = BotRepositoryFactory.DEFAULT_BOT_ID

        repo.appendMessage("bot_b", ChatRole.AI, "后台消息", ts = 1000L)

        assertEquals(1, repo.conversation("bot_b")!!.unread)
        assertEquals(1, repo.totalUnread())
    }

    @Test
    fun `向不存在的机器人追加消息被拒绝 迟到帧不应凭空创建会话`() {
        val repo = newRepo()

        val result = repo.appendMessage("bot_removed", ChatRole.AI, "孤儿消息", ts = 1000L)

        assertNull(result)
        assertTrue(repo.conversations().isEmpty())
    }

    @Test
    fun `消息数超过上限时从头裁剪`() {
        val repo = newRepo()
        val botId = BotRepositoryFactory.DEFAULT_BOT_ID

        for (i in 1..600) {
            repo.appendMessage(botId, ChatRole.USER, "msg-$i", ts = i.toLong())
        }

        val messages = repo.messages(botId)
        assertEquals(500, messages.size)
        // 最早的 100 条被裁掉，保留 101..600
        assertEquals("msg-101", messages.first().content)
        assertEquals("msg-600", messages.last().content)
    }

    // ---------------- 会话排序 ----------------

    @Test
    fun `会话列表按最后消息时间倒序`() {
        val repo = newRepo()
        repo.addBot(bot("bot_b", "小学", "11:22:33:44:55:66"))
        repo.addBot(bot("bot_c", "小娱", "22:33:44:55:66:77"))

        repo.appendMessage(BotRepositoryFactory.DEFAULT_BOT_ID, ChatRole.USER, "早", ts = 1000L)
        repo.appendMessage("bot_c", ChatRole.USER, "中", ts = 3000L)
        repo.appendMessage("bot_b", ChatRole.USER, "晚", ts = 2000L)

        assertEquals(
            listOf("bot_c", "bot_b", BotRepositoryFactory.DEFAULT_BOT_ID),
            repo.conversations().map { it.botId },
        )
    }

    // ---------------- 机器人增删 ----------------

    @Test
    fun `按 MAC 查机器人忽略大小写`() {
        val repo = newRepo()

        assertEquals(
            BotRepositoryFactory.DEFAULT_BOT_ID,
            repo.botByMac("AA:BB:CC:DD:EE:FF")?.id,
        )
    }

    @Test
    fun `删除机器人同时清除其会话`() {
        val repo = newRepo()
        repo.addBot(bot("bot_b", "小学", "11:22:33:44:55:66"))
        repo.appendMessage("bot_b", ChatRole.AI, "同学你好", ts = 1000L)

        assertTrue(repo.removeBot("bot_b"))

        assertNull(repo.bot("bot_b"))
        assertNull(repo.conversation("bot_b"))
        assertEquals(0, repo.totalUnread())
    }

    @Test
    fun `最后一个机器人不允许删除`() {
        val repo = newRepo()

        assertFalse(repo.removeBot(BotRepositoryFactory.DEFAULT_BOT_ID))
        assertEquals(1, repo.bots().size)
    }

    @Test
    fun `删除唤醒目标后唤醒目标改指其它机器人`() {
        val repo = newRepo()
        repo.addBot(bot("bot_b", "小学", "11:22:33:44:55:66"))
        repo.wakeTargetBotId = "bot_b"
        repo.activeBotId = "bot_b"

        repo.removeBot("bot_b")

        assertEquals(BotRepositoryFactory.DEFAULT_BOT_ID, repo.wakeTargetBotId)
        assertEquals(BotRepositoryFactory.DEFAULT_BOT_ID, repo.activeBotId)
    }

    // ---------------- 持久化 ----------------

    @Test
    fun `重建仓库后数据完整恢复`() {
        val repo = newRepo()
        repo.addBot(bot("bot_b", "小学", "11:22:33:44:55:66"))
        repo.appendMessage("bot_b", ChatRole.AI, "同学你好", ts = 1000L)
        repo.appendMessage("bot_b", ChatRole.USER, "讲讲二次方程", ts = 2000L)
        repo.wakeTargetBotId = "bot_b"
        repo.markActivated("bot_b", true)
        repo.flush()

        val reopened = newRepo()

        assertEquals(2, reopened.bots().size)
        assertEquals("bot_b", reopened.wakeTargetBotId)
        assertTrue(reopened.bot("bot_b")!!.activated)
        val messages = reopened.messages("bot_b")
        assertEquals(2, messages.size)
        assertEquals(ChatRole.AI, messages[0].role)
        assertEquals("讲讲二次方程", messages[1].content)
        // AI 消息带来的未读也应保留
        assertEquals(1, reopened.totalUnread())
    }

    @Test
    fun `JSON 损坏时回落到 seed 而不是崩溃`() {
        newRepo().flush()
        file.writeText("{ this is not json")

        val repo = newRepo()

        assertEquals(1, repo.bots().size)
        assertEquals(BotRepositoryFactory.DEFAULT_BOT_ID, repo.bots()[0].id)
    }

    @Test
    fun `版本不匹配时重建为 seed`() {
        val stale = BotRepositoryFactory.seed(deviceMac).apply {
            version = AppData.CURRENT_VERSION + 1
            bots.add(bot("bot_stale", "旧机器人", "99:99:99:99:99:99"))
        }
        file.writeText(gson.toJson(stale))

        val repo = newRepo()

        assertEquals(1, repo.bots().size)
        assertNull(repo.bot("bot_stale"))
    }

    @Test
    fun `机器人列表为空的存档视为无效 回落到 seed`() {
        val empty = AppData(version = AppData.CURRENT_VERSION)
        file.writeText(gson.toJson(empty))

        val repo = newRepo()

        assertEquals(1, repo.bots().size)
    }

    @Test
    fun `重置后回到首启状态并落盘`() {
        val repo = newRepo()
        repo.addBot(bot("bot_b", "小学", "11:22:33:44:55:66"))
        repo.appendMessage("bot_b", ChatRole.AI, "同学你好", ts = 1000L)

        repo.resetAll()
        repo.flush()

        assertEquals(1, repo.bots().size)
        assertTrue(repo.conversations().isEmpty())
        // 落盘生效：重开仍是 seed 状态，而不是仅内存被清
        val reopened = newRepo()
        assertEquals(1, reopened.bots().size)
        assertNull(reopened.bot("bot_b"))
    }

    @Test
    fun `数据变更会触发回调`() {
        val repo = newRepo()
        var count = 0
        repo.onDataChanged = { count++ }

        repo.appendMessage(BotRepositoryFactory.DEFAULT_BOT_ID, ChatRole.USER, "在吗", ts = 1L)
        repo.wakeTargetBotId = "bot_other"

        assertEquals(2, count)
    }

    @Test
    fun `设置相同的唤醒目标不重复触发回调`() {
        val repo = newRepo()
        var count = 0
        repo.onDataChanged = { count++ }

        repo.wakeTargetBotId = BotRepositoryFactory.DEFAULT_BOT_ID

        assertEquals(0, count)
    }

    @Test
    fun `落盘文件是合法 JSON 且包含关键字段`() {
        val repo = newRepo()
        repo.appendMessage(BotRepositoryFactory.DEFAULT_BOT_ID, ChatRole.AI, "你好", ts = 1L)
        repo.flush()
        // flush 后 IO 线程可能仍在排队，给它一点时间
        Thread.sleep(300)

        val parsed = gson.fromJson(file.readText(), AppData::class.java)
        assertNotNull(parsed)
        assertEquals(AppData.CURRENT_VERSION, parsed.version)
        assertEquals(1, parsed.bots.size)
        assertEquals(1, parsed.conversations.size)
        assertEquals(1, parsed.conversations[0].unread)
    }

    private fun bot(id: String, name: String, mac: String) = Bot(
        id = id,
        name = name,
        avatarText = name.take(1),
        avatarColorIndex = 1,
        tags = listOf("测试"),
        desc = "测试机器人",
        mac = mac,
        createdAt = 0L,
    )
}
