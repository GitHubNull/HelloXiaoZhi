package org.oxff.helloxiaozhi.robot

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxff.helloxiaozhi.music.MusicLibrary
import org.oxff.helloxiaozhi.music.MusicPlayer
import org.oxff.helloxiaozhi.music.MusicSource
import org.oxff.helloxiaozhi.music.MusicTrack
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * RobotActionRegistry 单元测试：动作注册、查找、执行与 tools/list JSON 生成。
 */
@RunWith(RobolectricTestRunner::class)
class RobotActionRegistryTest {

    /** 记录调用的 mock 执行器 */
    private class RecordingExecutor : RobotActionExecutor {
        val calls = mutableListOf<String>()

        override fun rotateHead(angle: Float, speed: Int) { calls.add("rotateHead($angle,$speed)") }
        override fun nodHead(speed: Int) { calls.add("nodHead($speed)") }
        override fun shakeHead(speed: Int) { calls.add("shakeHead($speed)") }
        override fun moveForward(speed: Float, duration: Long) { calls.add("moveForward($speed,$duration)") }
        override fun moveBackward(speed: Float, duration: Long) { calls.add("moveBackward($speed,$duration)") }
        override fun turnLeft(speed: Float, angle: Float) { calls.add("turnLeft($speed,$angle)") }
        override fun turnRight(speed: Float, angle: Float) { calls.add("turnRight($speed,$angle)") }
        override fun stopMoving() { calls.add("stopMoving") }
        override fun showEmotion(name: String, speaking: Boolean, loops: Int) { calls.add("showEmotion($name,$speaking,$loops)") }
        override fun dismissEmotion() { calls.add("dismissEmotion") }
        override fun expressAgreement() { calls.add("expressAgreement") }
        override fun expressDisagreement() { calls.add("expressDisagreement") }
        override fun expressCuriosity() { calls.add("expressCuriosity") }
        override fun expressExcitement() { calls.add("expressExcitement") }
        override fun expressLove() { calls.add("expressLove") }
        override fun expressShyness() { calls.add("expressShyness") }
        override fun expressSurprise() { calls.add("expressSurprise") }
        override fun waveHello() { calls.add("waveHello") }
        override fun dance() { calls.add("dance") }
        override fun think() { calls.add("think") }
        override fun resetToDefault() { calls.add("resetToDefault") }
    }

    private fun newRegistry(): Pair<RobotActionRegistry, RecordingExecutor> {
        val executor = RecordingExecutor()
        return RobotActionRegistry(executor) to executor
    }

    private fun newRegistryWithMusic(): Triple<RobotActionRegistry, RecordingExecutor, FakeMusicPlayer> {
        val executor = RecordingExecutor()
        val context = RuntimeEnvironment.getApplication()
        val musicPlayer = FakeMusicPlayer(context)
        val musicLibrary = FakeMusicLibrary(context)
        return Triple(RobotActionRegistry(executor, musicPlayer, musicLibrary), executor, musicPlayer)
    }

    // ---------------- 注册与查找 ----------------

    @Test
    fun `注册表包含全部动作分类`() {
        val (registry, _) = newRegistry()
        val actions = registry.allActions()
        val categories = actions.map { it.category }.toSet()
        assertTrue(Category.HEAD in categories)
        assertTrue(Category.MOTION in categories)
        assertTrue(Category.EMOTION in categories)
        assertTrue(Category.COMBO in categories)
    }

    @Test
    fun `按名称查找已注册动作`() {
        val (registry, _) = newRegistry()
        assertNotNull(registry.findAction("self.robot.nod"))
        assertNotNull(registry.findAction("self.robot.move_forward"))
        assertNotNull(registry.findAction("self.robot.show_emotion"))
        assertNotNull(registry.findAction("self.robot.dance"))
    }

    @Test
    fun `查找未注册动作返回 null`() {
        val (registry, _) = newRegistry()
        assertNull(registry.findAction("self.robot.nonexistent"))
    }

    // ---------------- 执行 ----------------

    @Test
    fun `执行点头动作`() {
        val (registry, executor) = newRegistry()
        assertNotNull(registry.execute("self.robot.nod", emptyMap()))
        assertTrue(executor.calls.any { it.startsWith("nodHead") })
    }

    @Test
    fun `执行移动动作带参数`() {
        val (registry, executor) = newRegistry()
        assertNotNull(registry.execute("self.robot.move_forward", mapOf("speed" to 0.5, "duration" to 2000)))
        assertTrue(executor.calls.any { it.contains("moveForward") && it.contains("0.5") })
    }

    @Test
    fun `执行转向动作带参数`() {
        val (registry, executor) = newRegistry()
        assertNotNull(registry.execute("self.robot.turn_left", mapOf("angle" to 45, "speed" to 20)))
        assertTrue(executor.calls.any { it.contains("turnLeft") })
    }

    @Test
    fun `执行表情动作`() {
        val (registry, executor) = newRegistry()
        assertNotNull(registry.execute("self.robot.show_emotion", mapOf("name" to "happy", "speaking" to true)))
        assertTrue(executor.calls.any { it.contains("showEmotion") && it.contains("happy") })
    }

    @Test
    fun `执行组合动作`() {
        val (registry, executor) = newRegistry()
        assertNotNull(registry.execute("self.robot.dance", emptyMap()))
        assertTrue(executor.calls.contains("dance"))
    }

    @Test
    fun `执行动作成功返回 ok JSON`() {
        val (registry, _) = newRegistry()
        val result = registry.execute("self.robot.nod", emptyMap())
        assertNotNull(result)
        val parsed = JsonParser.parseString(result).asJsonObject
        assertEquals("ok", parsed.get("result").asString)
    }

    @Test
    fun `执行未知动作返回 null`() {
        val (registry, _) = newRegistry()
        assertNull(registry.execute("self.robot.nonexistent", emptyMap()))
    }

    @Test
    fun `执行头部旋转缺少必填参数返回 null`() {
        val (registry, _) = newRegistry()
        assertNull(registry.execute("self.robot.head_rotate", emptyMap()))
    }

    // ---------------- tools/list JSON ----------------

    @Test
    fun `tools list JSON 包含所有注册动作`() {
        val (registry, _) = newRegistry()
        val tools = registry.buildToolsListJson()
        assertEquals(registry.allActions().size, tools.size())
    }

    @Test
    fun `tools list JSON 中每个工具包含 name 与 description`() {
        val (registry, _) = newRegistry()
        val tools = registry.buildToolsListJson()
        for (i in 0 until tools.size()) {
            val tool = tools[i].asJsonObject
            assertTrue(tool.has("name"))
            assertTrue(tool.has("description"))
            assertTrue(tool.has("inputSchema"))
        }
    }

    @Test
    fun `tools list JSON 中头部旋转工具包含 angle 参数`() {
        val (registry, _) = newRegistry()
        val tools = registry.buildToolsListJson()
        val headRotate = (0 until tools.size())
            .map { tools[it].asJsonObject }
            .first { it.get("name").asString == "self.robot.head_rotate" }
        val props = headRotate.getAsJsonObject("inputSchema").getAsJsonObject("properties")
        assertTrue(props.has("angle"))
        assertTrue(props.has("speed"))
    }

    @Test
    fun `tools list JSON 中表情工具包含 enum 值`() {
        val (registry, _) = newRegistry()
        val tools = registry.buildToolsListJson()
        val emotion = (0 until tools.size())
            .map { tools[it].asJsonObject }
            .first { it.get("name").asString == "self.robot.show_emotion" }
        val props = emotion.getAsJsonObject("inputSchema").getAsJsonObject("properties")
        val nameParam = props.getAsJsonObject("name")
        assertTrue(nameParam.has("enum"))
        val enumValues = nameParam.getAsJsonArray("enum").map { it.asString }
        assertTrue("happy" in enumValues)
        assertTrue("excited" in enumValues)
    }

    // ---------------- 音乐动作 ----------------

    @Test
    fun `注册表包含音乐动作分类`() {
        val (registry, _, _) = newRegistryWithMusic()
        val actions = registry.allActions()
        val categories = actions.map { it.category }.toSet()
        assertTrue(Category.MUSIC in categories)
    }

    @Test
    fun `按名称查找音乐动作`() {
        val (registry, _, _) = newRegistryWithMusic()
        assertNotNull(registry.findAction("self.music.play"))
        assertNotNull(registry.findAction("self.music.pause"))
        assertNotNull(registry.findAction("self.music.stop"))
        assertNotNull(registry.findAction("self.music.next"))
        assertNotNull(registry.findAction("self.music.previous"))
    }

    @Test
    fun `执行音乐播放动作`() {
        val (registry, _, musicPlayer) = newRegistryWithMusic()
        assertNotNull(registry.execute("self.music.play", mapOf("track" to "晴天")))
        assertTrue(musicPlayer.playCalled)
    }

    @Test
    fun `执行音乐暂停动作`() {
        val (registry, _, musicPlayer) = newRegistryWithMusic()
        assertNotNull(registry.execute("self.music.pause", emptyMap()))
        assertTrue(musicPlayer.pauseCalled)
    }

    @Test
    fun `执行音乐停止动作`() {
        val (registry, _, musicPlayer) = newRegistryWithMusic()
        assertNotNull(registry.execute("self.music.stop", emptyMap()))
        assertTrue(musicPlayer.stopCalled)
    }

    @Test
    fun `执行音乐下一首动作`() {
        val (registry, _, musicPlayer) = newRegistryWithMusic()
        assertNotNull(registry.execute("self.music.next", emptyMap()))
        assertTrue(musicPlayer.nextCalled)
    }

    @Test
    fun `执行音乐上一首动作`() {
        val (registry, _, musicPlayer) = newRegistryWithMusic()
        assertNotNull(registry.execute("self.music.previous", emptyMap()))
        assertTrue(musicPlayer.previousCalled)
    }

    @Test
    fun `音乐动作 tools list JSON 包含 play 工具`() {
        val (registry, _, _) = newRegistryWithMusic()
        val tools = registry.buildToolsListJson()
        val playTool = (0 until tools.size())
            .map { tools[it].asJsonObject }
            .first { it.get("name").asString == "self.music.play" }
        val props = playTool.getAsJsonObject("inputSchema").getAsJsonObject("properties")
        assertTrue(props.has("track"))
        assertTrue(props.has("genre"))
        assertTrue(props.has("random"))
        assertTrue(props.has("artist"))
        assertTrue(props.has("album"))
    }

    @Test
    fun `按名称查找音乐查询工具`() {
        val (registry, _, _) = newRegistryWithMusic()
        assertNotNull(registry.findAction("self.music.list"))
        assertNotNull(registry.findAction("self.music.search"))
        assertNotNull(registry.findAction("self.music.artists"))
        assertNotNull(registry.findAction("self.music.genres"))
        assertNotNull(registry.findAction("self.music.albums"))
    }

    @Test
    fun `音乐列表工具返回曲目 JSON`() {
        val (registry, _, _) = newRegistryWithMusic()
        val result = registry.execute("self.music.list", emptyMap())
        assertNotNull(result)
        val parsed = JsonParser.parseString(result).asJsonObject
        assertEquals(1, parsed.get("count").asInt)
        val first = parsed.getAsJsonArray("tracks")[0].asJsonObject
        assertEquals("晴天", first.get("title").asString)
        assertEquals("周杰伦", first.get("artist").asString)
        assertEquals("范特西", first.get("album").asString)
    }

    @Test
    fun `音乐搜索工具返回匹配曲目 JSON`() {
        val (registry, _, _) = newRegistryWithMusic()
        val result = registry.execute("self.music.search", mapOf("keyword" to "晴天"))
        assertNotNull(result)
        val parsed = JsonParser.parseString(result).asJsonObject
        assertEquals(1, parsed.get("count").asInt)
    }

    @Test
    fun `音乐搜索工具缺少关键词返回 null`() {
        val (registry, _, _) = newRegistryWithMusic()
        assertNull(registry.execute("self.music.search", emptyMap()))
    }

    @Test
    fun `音乐歌手工具返回统计 JSON`() {
        val (registry, _, _) = newRegistryWithMusic()
        val result = registry.execute("self.music.artists", emptyMap())
        assertNotNull(result)
        val parsed = JsonParser.parseString(result).asJsonObject
        assertEquals(1, parsed.get("count").asInt)
        val first = parsed.getAsJsonArray("artists")[0].asJsonObject
        assertEquals("周杰伦", first.get("artist").asString)
        assertEquals(1, first.get("tracks").asInt)
    }

    @Test
    fun `音乐专辑工具按歌手筛选`() {
        val (registry, _, _) = newRegistryWithMusic()
        val result = registry.execute("self.music.albums", mapOf("artist" to "周杰伦"))
        assertNotNull(result)
        val parsed = JsonParser.parseString(result).asJsonObject
        assertEquals(1, parsed.get("count").asInt)
        assertEquals("范特西", parsed.getAsJsonArray("albums")[0].asString)
    }

    @Test
    fun `按歌手播放返回 playing JSON`() {
        val (registry, _, musicPlayer) = newRegistryWithMusic()
        val result = registry.execute("self.music.play", mapOf("artist" to "周杰伦"))
        assertNotNull(result)
        assertTrue(musicPlayer.playCalled)
        val parsed = JsonParser.parseString(result).asJsonObject
        assertEquals("playing", parsed.get("result").asString)
        assertEquals("晴天", parsed.get("track").asString)
        assertEquals("周杰伦", parsed.get("artist").asString)
    }

    @Test
    fun `MCP track 参数为整句口语时归一化后命中`() {
        // 真机回归（2026-09-09 logcat）：服务器 AI 调 self.music.play 时把用户口语
        // 整句塞进 track 参数，旧实现直接 library.search(脏长串) 必然失配
        val (registry, _, musicPlayer) = newRegistryWithMusic()
        val result = registry.execute(
            "self.music.play",
            mapOf("track" to "你先帮我播放音乐吧，那个那个周杰伦的音乐。"),
        )
        assertNotNull(result)
        assertTrue(musicPlayer.playCalled)
    }

    @Test
    fun `MCP track 参数含填充词与语气词时归一化后命中`() {
        val (registry, _, musicPlayer) = newRegistryWithMusic()
        val result = registry.execute("self.music.play", mapOf("track" to "就是那个晴天吧"))
        assertNotNull(result)
        assertTrue(musicPlayer.playCalled)
    }

    @Test
    fun `播放无匹配返回 null`() {
        val (registry, _, musicPlayer) = newRegistryWithMusic()
        assertNull(registry.execute("self.music.play", mapOf("track" to "不存在的歌")))
        assertFalse(musicPlayer.playCalled)
    }

    // ---------------- 测试替身 ----------------

    private class FakeMusicPlayer(context: android.content.Context) : MusicPlayer(context) {
        var playCalled = false
        var pauseCalled = false
        var stopCalled = false
        var nextCalled = false
        var previousCalled = false

        override fun play(track: MusicTrack) {
            playCalled = true
        }

        override fun pause() {
            pauseCalled = true
        }

        override fun stop() {
            stopCalled = true
        }

        override fun next(): Boolean {
            nextCalled = true
            return true
        }

        override fun previous(): Boolean {
            previousCalled = true
            return true
        }
    }

    private class FakeMusicLibrary(context: android.content.Context) : MusicLibrary(
        context,
        // 惰性创建内存 DB：避免 Robolectric 沙箱类加载器中重复加载 SQLite native 库
        lazy {
            androidx.room.Room.inMemoryDatabaseBuilder(
                context,
                org.oxff.helloxiaozhi.data.db.MusicDatabase::class.java,
            ).allowMainThreadQueries().build()
        }.value,
    ) {
        var tracks: List<MusicTrack> = listOf(
            MusicTrack(
                id = "1", title = "晴天", artist = "周杰伦", album = "范特西",
                duration = 200000, path = "/music/sunny.mp3", source = MusicSource.LOCAL,
            ),
        )

        override fun allTracks(): List<MusicTrack> = tracks

        override fun search(keyword: String): List<MusicTrack> {
            val lower = keyword.lowercase()
            return tracks.filter {
                it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower)
            }
        }

        override fun randomTrack(): MusicTrack? = tracks.randomOrNull()

        override fun randomByGenre(genre: String): MusicTrack? = tracks.randomOrNull()

        override fun tracksByArtist(artist: String): List<MusicTrack> {
            val lower = artist.lowercase()
            return tracks.filter { it.artist.lowercase().contains(lower) }
        }

        override fun searchByAlbum(album: String): List<MusicTrack> {
            val lower = album.lowercase()
            return tracks.filter { it.album?.lowercase()?.contains(lower) == true }
        }

        override fun allAlbums(): List<String> =
            tracks.mapNotNull { it.album }.distinct().sorted()

        override fun artistTrackCounts(): Map<String, Int> =
            tracks.groupingBy { it.artist }.eachCount()

        override fun genreTrackCounts(): Map<String, Int> =
            tracks.filter { it.genre != null }.groupingBy { it.genre!! }.eachCount()
    }
}
