package org.oxff.helloxiaozhi.music

import android.net.Uri
import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxff.helloxiaozhi.data.db.MetadataSource
import org.oxff.helloxiaozhi.data.db.MusicDatabase
import org.oxff.helloxiaozhi.data.db.TrackMetadataCache
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * MusicLibrary 单元测试：音乐扫描、元数据提取、查询接口、缓存表读写。
 */
@RunWith(RobolectricTestRunner::class)
class MusicLibraryTest {

    private lateinit var db: MusicDatabase
    private lateinit var library: MusicLibrary

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MusicDatabase::class.java,
        ).allowMainThreadQueries().build()
        library = MusicLibrary(RuntimeEnvironment.getApplication(), db)
    }

    @After
    fun tearDown() {
        library.shutdown()
        db.close()
    }

    // ---------------- 查询接口 ----------------

    @Test
    fun `空库返回空列表`() {
        assertTrue(library.allTracks().isEmpty())
        assertEquals(0, library.trackCount())
        assertTrue(library.allArtists().isEmpty())
        assertTrue(library.allGenres().isEmpty())
        assertTrue(library.allAlbums().isEmpty())
    }

    @Test
    fun `空库搜索返回空列表`() {
        assertTrue(library.search("晴天").isEmpty())
        assertTrue(library.searchByTitle("晴天").isEmpty())
        assertTrue(library.searchByArtist("周杰伦").isEmpty())
        assertTrue(library.searchByAlbum("范特西").isEmpty())
        assertTrue(library.tracksByArtist("周杰伦").isEmpty())
    }

    @Test
    fun `空库统计返回空 Map`() {
        assertTrue(library.artistTrackCounts().isEmpty())
        assertTrue(library.genreTrackCounts().isEmpty())
    }

    @Test
    fun `空库随机返回 null`() {
        assertNull(library.randomTrack())
        assertNull(library.randomByGenre("轻音乐"))
    }

    @Test
    fun `搜索接口按标题匹配`() {
        // 注意：由于 MusicLibrary 的 tracks 是私有字段，无法直接注入测试数据
        // 此测试验证空库行为；实际搜索逻辑在 MusicActionMapperTest 中通过 FakeMusicLibrary 验证
        val results = library.searchByTitle("晴天")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `搜索接口按歌手匹配`() {
        val results = library.searchByArtist("周杰伦")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `搜索接口按标题或歌手匹配`() {
        val results = library.search("晴天")
        assertTrue(results.isEmpty())
    }

    // ---------------- 常量 ----------------

    @Test
    fun `未知歌手占位符常量`() {
        assertEquals("未知歌手", MusicLibrary.UNKNOWN_ARTIST)
    }

    // ---------------- 缓存表 ----------------

    @Test
    fun `缓存表写入与读取`() {
        val dao = db.trackMetadataCacheDao()
        dao.upsertAll(
            listOf(
                TrackMetadataCache(
                    trackId = "1",
                    title = "晴天",
                    artist = "周杰伦",
                    album = "叶惠美",
                    genre = "流行",
                    duration = 240000L,
                    path = "/music/周杰伦/晴天.mp3",
                    lastModified = 1000L,
                    metadataSource = MetadataSource.ID3,
                ),
                TrackMetadataCache(
                    trackId = "2",
                    title = "舞女",
                    artist = "韩宝仪",
                    album = null,
                    genre = "经典老歌",
                    duration = 200000L,
                    path = "/music/韩宝仪/舞女.mp3",
                    lastModified = 2000L,
                    metadataSource = MetadataSource.DESCRIPTION_FILE,
                ),
            ),
        )
        val all = dao.all()
        assertEquals(2, all.size)
        assertEquals("韩宝仪", all.first { it.trackId == "2" }.artist)
        assertEquals(MetadataSource.DESCRIPTION_FILE, all.first { it.trackId == "2" }.metadataSource)
    }

    @Test
    fun `缓存表 upsert 按主键覆盖`() {
        val dao = db.trackMetadataCacheDao()
        val entry = TrackMetadataCache(
            trackId = "1",
            title = "晴天",
            artist = "周杰伦",
            album = null,
            genre = null,
            duration = 240000L,
            path = "/music/周杰伦/晴天.mp3",
            lastModified = 1000L,
            metadataSource = MetadataSource.ID3,
        )
        dao.upsertAll(listOf(entry))
        dao.upsertAll(listOf(entry.copy(title = "晴天（重制）")))
        assertEquals(1, dao.count())
        assertEquals("晴天（重制）", dao.all().first().title)
    }

    @Test
    fun `缓存表按目录前缀清除`() {
        val dao = db.trackMetadataCacheDao()
        fun entry(id: String, path: String) = TrackMetadataCache(
            trackId = id,
            title = id,
            artist = "测试",
            album = null,
            genre = null,
            duration = 0L,
            path = path,
            lastModified = 0L,
            metadataSource = MetadataSource.FOLDER_NAME,
        )
        dao.upsertAll(
            listOf(
                entry("1", "/music/周杰伦/晴天.mp3"),
                entry("2", "/music/周杰伦/安静.mp3"),
                entry("3", "/music/林俊杰/江南.mp3"),
            ),
        )
        dao.deleteByPathPrefix("/music/周杰伦")
        val remain = dao.all()
        assertEquals(1, remain.size)
        assertEquals("/music/林俊杰/江南.mp3", remain.first().path)
    }

    @Test
    fun `缓存表清空`() {
        val dao = db.trackMetadataCacheDao()
        dao.upsertAll(
            listOf(
                TrackMetadataCache(
                    trackId = "1",
                    title = "晴天",
                    artist = "周杰伦",
                    album = null,
                    genre = null,
                    duration = 0L,
                    path = "/music/周杰伦/晴天.mp3",
                    lastModified = 0L,
                    metadataSource = MetadataSource.ID3,
                ),
            ),
        )
        dao.clearAll()
        assertEquals(0, dao.count())
    }

    // ---------------- 扫描状态 ----------------

    @Test
    fun `初始状态不在扫描中`() {
        assertTrue(!library.isScanning)
    }

    // ---------------- 音频文件过滤 ----------------

    @Test
    fun `音频文件扩展名过滤`() {
        // 通过反射调用私有方法
        val method = MusicLibrary::class.java.getDeclaredMethod("isAudioFile", String::class.java)
        method.isAccessible = true

        assertTrue(method.invoke(library, "song.mp3") as Boolean)
        assertTrue(method.invoke(library, "song.wav") as Boolean)
        assertTrue(method.invoke(library, "song.flac") as Boolean)
        assertTrue(method.invoke(library, "song.aac") as Boolean)
        assertTrue(method.invoke(library, "song.ogg") as Boolean)
        assertTrue(method.invoke(library, "song.m4a") as Boolean)
        assertTrue(!(method.invoke(library, "song.txt") as Boolean))
        assertTrue(!(method.invoke(library, "song.pdf") as Boolean))
        assertTrue(!(method.invoke(library, "song") as Boolean))
    }

    // ---------------- SAF 推断路径 --------------

    @Test
    fun `SAF URI 解码出相对路径而非 document 段`() {
        // 模拟 ExternalStorageProvider 的 document URI：目录层次编码在 documentId（%2F）中
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3Afree" +
                "/document/primary%3Afree%2F%E9%9F%A9%E5%AE%9D%E4%BB%AA1%2F%E6%98%A8%E5%A4%9C%E6%A2%A6%E9%86%92%E6%97%B6.mp3",
        )
        val method = MusicLibrary::class.java.getDeclaredMethod("buildSafInferPath", Uri::class.java, String::class.java)
        method.isAccessible = true
        val decoded = method.invoke(library, uri, "昨夜梦醒时.mp3") as String
        assertEquals("primary:free/韩宝仪1/昨夜梦醒时.mp3", decoded)
        // 不应再出现 "document" 字面量（原 bug 会将 /document/ 段误当父目录名）
        assertTrue(!decoded.contains("document"))
    }
}
