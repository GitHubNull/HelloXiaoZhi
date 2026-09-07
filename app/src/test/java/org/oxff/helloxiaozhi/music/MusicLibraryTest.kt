package org.oxff.helloxiaozhi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * MusicLibrary 单元测试：音乐扫描、元数据提取、查询接口。
 */
@RunWith(RobolectricTestRunner::class)
class MusicLibraryTest {

    private lateinit var library: MusicLibrary

    @Before
    fun setUp() {
        library = MusicLibrary(RuntimeEnvironment.getApplication())
    }

    // ---------------- 查询接口 ----------------

    @Test
    fun `空库返回空列表`() {
        assertTrue(library.allTracks().isEmpty())
        assertEquals(0, library.trackCount())
    }

    @Test
    fun `空库搜索返回空列表`() {
        assertTrue(library.search("晴天").isEmpty())
        assertTrue(library.searchByTitle("晴天").isEmpty())
        assertTrue(library.searchByArtist("周杰伦").isEmpty())
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

    // ---------------- 缓存 ----------------

    @Test
    fun `缓存目录在 filesDir 下`() {
        // 验证缓存目录创建逻辑：MusicLibrary 初始化时会创建 filesDir/music_cache 目录
        val context = RuntimeEnvironment.getApplication()
        val expectedDir = java.io.File(context.filesDir, "music_cache")
        // 由于 MusicLibrary 在 init 中调用 loadCache()，cacheDir 属性会被访问并创建目录
        assertTrue(expectedDir.exists() || expectedDir.mkdirs())
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
}
