package org.oxff.helloxiaozhi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * MusicActionMapper 单元测试：关键词匹配规则。
 */
@RunWith(RobolectricTestRunner::class)
class MusicActionMapperTest {

    private lateinit var musicPlayer: FakeMusicPlayer
    private lateinit var musicLibrary: FakeMusicLibrary
    private lateinit var mapper: MusicActionMapper

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        musicPlayer = FakeMusicPlayer(context)
        musicLibrary = FakeMusicLibrary(context)
        mapper = MusicActionMapper(musicPlayer, musicLibrary)
    }

    // ---------------- 播放控制 ----------------

    @Test
    fun `播放指定曲目`() {
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "晴天", artist = "周杰伦", album = "范特西",
                duration = 200000, path = "/music/sunny.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放晴天"))
        assertEquals("晴天", musicPlayer.lastPlayedTrack?.title)
    }

    @Test
    fun `播放指定歌手`() {
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "晴天", artist = "周杰伦", album = "范特西",
                duration = 200000, path = "/music/sunny.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放周杰伦的歌"))
        assertEquals("周杰伦", musicPlayer.lastPlayedTrack?.artist)
    }

    @Test
    fun `播放指定歌手带歌曲后缀`() {
        // 回归：之前 "播放韩宝仪的歌曲" 后缀 "歌曲" 未去除，
        // 导致搜索关键词为 "韩宝仪的歌曲" 匹配失败，本地不播放
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "想要潇洒离开", artist = "韩宝仪", album = null,
                duration = 200000, path = "/music/xiaosa.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放韩宝仪的歌曲"))
        assertEquals("韩宝仪", musicPlayer.lastPlayedTrack?.artist)
    }

    @Test
    fun `播放指定歌手带歌曲后缀加句号`() {
        // 回归：STT 识别文本末尾带中文句号，导致 endsWith("的歌曲") 失效，
        // 关键词仍为 "韩宝仪的歌曲。" 匹配失败，本地不播放
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "想要潇洒离开", artist = "韩宝仪", album = null,
                duration = 200000, path = "/music/xiaosa.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放韩宝仪的歌曲。"))
        assertEquals("韩宝仪", musicPlayer.lastPlayedTrack?.artist)
    }

    @Test
    fun `播放指定歌手带给我前缀加句号`() {
        // 回归：用户实测 "给我播放韩宝仪的歌曲。" 提取出带 "的歌曲。" 的关键词匹配失败
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "想要潇洒离开", artist = "韩宝仪", album = null,
                duration = 200000, path = "/music/xiaosa.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("给我播放韩宝仪的歌曲。"))
        assertEquals("韩宝仪", musicPlayer.lastPlayedTrack?.artist)
    }

    @Test
    fun `播放书名词加句号`() {
        // 回归：用户实测 "播放《想要潇洒的离开》" 识别成 "播放想要潇洒的离开。"
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "想要潇洒的离开", artist = "韩宝仪", album = null,
                duration = 200000, path = "/music/xiaosa.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放想要潇洒的离开。"))
        assertEquals("想要潇洒的离开", musicPlayer.lastPlayedTrack?.title)
    }

    @Test
    fun `播放一首加歌手加歌曲后缀加句号`() {
        // 回归：用户实测 "播放一首韩宝仪的歌曲。" 提取出 "一首韩宝仪"（带头部量词"一首"）导致匹配失败
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "想要潇洒的离开", artist = "韩宝仪", album = null,
                duration = 200000, path = "/music/xiaosa.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放一首韩宝仪的歌曲。"))
        assertEquals("韩宝仪", musicPlayer.lastPlayedTrack?.artist)
    }

    @Test
    fun `播放一首书名词加句号`() {
        // 回归：用户实测 "播放一首《想要潇洒的离开》" 识别成 "播放一首想要潇洒的离开。"
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "想要潇洒的离开", artist = "韩宝仪", album = null,
                duration = 200000, path = "/music/xiaosa.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放一首想要潇洒的离开。"))
        assertEquals("想要潇洒的离开", musicPlayer.lastPlayedTrack?.title)
    }

    @Test
    fun `播放带口语前缀的歌曲`() {
        // 回归：用户实测 "你再帮我播放一首韩宝仪的歌曲。"
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "想要潇洒的离开", artist = "韩宝仪", album = null,
                duration = 200000, path = "/music/xiaosa.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("你再帮我播放一首韩宝仪的歌曲。"))
        assertEquals("韩宝仪", musicPlayer.lastPlayedTrack?.artist)
    }

    @Test
    fun `播放指定歌手带音乐后缀`() {
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "晴天", artist = "周杰伦", album = "范特西",
                duration = 200000, path = "/music/sunny.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放周杰伦的音乐"))
        assertEquals("周杰伦", musicPlayer.lastPlayedTrack?.artist)
    }

    @Test
    fun `播放指定歌曲带首歌后缀`() {
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "晴天", artist = "周杰伦", album = "范特西",
                duration = 200000, path = "/music/sunny.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放晴天这首歌"))
        assertEquals("晴天", musicPlayer.lastPlayedTrack?.title)
    }

    @Test
    fun `随机播放`() {
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "晴天", artist = "周杰伦", album = "范特西",
                duration = 200000, path = "/music/sunny.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("随机播放"))
        assertTrue(musicPlayer.lastPlayedTrack != null)
    }

    @Test
    fun `按类型播放`() {
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "轻音乐1", artist = "未知",
                duration = 200000, path = "/music/light1.mp3", source = MusicSource.LOCAL, genre = "轻音乐",
            ),
        )
        assertTrue(mapper.processText("来点轻音乐"))
        assertEquals("轻音乐1", musicPlayer.lastPlayedTrack?.title)
    }

    @Test
    fun `按专辑播放`() {
        musicLibrary.tracks = listOf(
            MusicTrack(
                id = "1", title = "晴天", artist = "周杰伦", album = "范特西",
                duration = 200000, path = "/music/sunny.mp3", source = MusicSource.LOCAL,
            ),
        )
        assertTrue(mapper.processText("播放范特西的专辑"))
        assertEquals(1, musicPlayer.lastPlaylist?.size)
        assertEquals("晴天", musicPlayer.lastPlaylist?.first()?.title)
    }

    @Test
    fun `专辑无匹配返回 false`() {
        musicLibrary.tracks = emptyList()
        assertFalse(mapper.processText("播放不存在专辑的专辑"))
    }

    @Test
    fun `暂停播放`() {
        assertTrue(mapper.processText("暂停播放"))
        assertTrue(musicPlayer.pauseCalled)
    }

    @Test
    fun `继续播放`() {
        assertTrue(mapper.processText("继续播放"))
        assertTrue(musicPlayer.resumeCalled)
    }

    @Test
    fun `停止播放`() {
        assertTrue(mapper.processText("停止播放"))
        assertTrue(musicPlayer.stopCalled)
    }

    @Test
    fun `下一首`() {
        assertTrue(mapper.processText("下一首"))
        assertTrue(musicPlayer.nextCalled)
    }

    @Test
    fun `上一首`() {
        assertTrue(mapper.processText("上一首"))
        assertTrue(musicPlayer.previousCalled)
    }

    @Test
    fun `未匹配返回 false`() {
        assertFalse(mapper.processText("今天天气怎么样"))
    }

    @Test
    fun `禁用时不匹配`() {
        mapper.enabled = false
        assertFalse(mapper.processText("播放晴天"))
    }

    // ---------------- 测试替身 ----------------

    private class FakeMusicPlayer(context: android.content.Context) : MusicPlayer(context) {
        var lastPlayedTrack: MusicTrack? = null
        var lastPlaylist: List<MusicTrack>? = null
        var pauseCalled = false
        var resumeCalled = false
        var stopCalled = false
        var nextCalled = false
        var previousCalled = false

        override fun play(track: MusicTrack) {
            lastPlayedTrack = track
        }

        override fun playPlaylist(tracks: List<MusicTrack>, startIndex: Int) {
            lastPlaylist = tracks
        }

        override fun pause() {
            pauseCalled = true
        }

        override fun resume() {
            resumeCalled = true
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
        var tracks: List<MusicTrack> = emptyList()

        override fun allTracks(): List<MusicTrack> = tracks

        override fun search(keyword: String): List<MusicTrack> {
            val lower = keyword.lowercase()
            return tracks.filter {
                it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower)
            }
        }

        override fun randomTrack(): MusicTrack? = tracks.randomOrNull()

        override fun randomByGenre(genre: String): MusicTrack? {
            val lower = genre.lowercase()
            return tracks.filter { it.genre?.lowercase()?.contains(lower) == true }.randomOrNull()
                ?: tracks.randomOrNull()
        }

        override fun searchByAlbum(album: String): List<MusicTrack> {
            val lower = album.lowercase()
            return tracks.filter { it.album?.lowercase()?.contains(lower) == true }
        }

        override fun tracksByArtist(artist: String): List<MusicTrack> {
            val lower = artist.lowercase()
            return tracks.filter { it.artist.lowercase().contains(lower) }
        }
    }
}
