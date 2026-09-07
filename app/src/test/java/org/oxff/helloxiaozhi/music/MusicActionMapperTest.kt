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
            MusicTrack("1", "晴天", "周杰伦", 200000, "/music/sunny.mp3", MusicSource.LOCAL),
        )
        assertTrue(mapper.processText("播放晴天"))
        assertEquals("晴天", musicPlayer.lastPlayedTrack?.title)
    }

    @Test
    fun `播放指定歌手`() {
        musicLibrary.tracks = listOf(
            MusicTrack("1", "晴天", "周杰伦", 200000, "/music/sunny.mp3", MusicSource.LOCAL),
        )
        assertTrue(mapper.processText("播放周杰伦的歌"))
        assertEquals("周杰伦", musicPlayer.lastPlayedTrack?.artist)
    }

    @Test
    fun `随机播放`() {
        musicLibrary.tracks = listOf(
            MusicTrack("1", "晴天", "周杰伦", 200000, "/music/sunny.mp3", MusicSource.LOCAL),
        )
        assertTrue(mapper.processText("随机播放"))
        assertTrue(musicPlayer.lastPlayedTrack != null)
    }

    @Test
    fun `按类型播放`() {
        musicLibrary.tracks = listOf(
            MusicTrack("1", "轻音乐1", "未知", 200000, "/music/light1.mp3", MusicSource.LOCAL, genre = "轻音乐"),
        )
        assertTrue(mapper.processText("来点轻音乐"))
        assertEquals("轻音乐1", musicPlayer.lastPlayedTrack?.title)
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
        var pauseCalled = false
        var resumeCalled = false
        var stopCalled = false
        var nextCalled = false
        var previousCalled = false

        override fun play(track: MusicTrack) {
            lastPlayedTrack = track
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

    private class FakeMusicLibrary(context: android.content.Context) : MusicLibrary(context) {
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
    }
}
