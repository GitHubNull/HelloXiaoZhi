package org.oxff.helloxiaozhi.music

import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxff.helloxiaozhi.data.db.ArtistSeedData
import org.oxff.helloxiaozhi.data.db.ArtistType
import org.oxff.helloxiaozhi.data.db.MusicDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * ArtistDictionary 单元测试（Robolectric + 内存 Room 数据库）。
 *
 * 覆盖：种子数据初始化、精确/别名/忽略大小写/模糊包含匹配、
 * 导入去重（含 BUILTIN 重复）、中日英文 UTF-8、删除/导出。
 */
@RunWith(RobolectricTestRunner::class)
class ArtistDictionaryTest {

    private lateinit var db: MusicDatabase
    private lateinit var dict: ArtistDictionary

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MusicDatabase::class.java,
        ).allowMainThreadQueries().build()
        // inMemoryDatabaseBuilder 对 onCreate 回调支持不稳定，显式幂等写入种子数据
        MusicDatabase.seedIfEmpty(db)
        dict = ArtistDictionary(db.artistDictDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------- 种子数据 ----------------

    @Test
    fun `种子数据在 onCreate 一次性写入`() {
        val expected = ArtistSeedData.STANDARD.size + ArtistSeedData.ALIASES.size
        assertEquals(expected, db.artistDictDao().count())
        assertEquals(expected, dict.builtinCount())
        // 用户导入条目初始为空
        assertEquals(0, dict.userImportedCount())
    }

    // ---------------- 匹配 ----------------

    @Test
    fun `精确匹配标准名`() {
        assertEquals("周杰伦", dict.matchArtist("周杰伦"))
        assertEquals("五月天", dict.matchArtist("五月天"))
    }

    @Test
    fun `忽略大小写匹配`() {
        assertEquals("Beyond", dict.matchArtist("beyond"))
        assertEquals("Beyond", dict.matchArtist("BEYOND"))
        assertEquals("Taylor Swift", dict.matchArtist("taylor swift"))
    }

    @Test
    fun `别名映射到标准名`() {
        assertEquals("周杰伦", dict.matchArtist("周董"))
        assertEquals("周杰伦", dict.matchArtist("Jay Chou"))
        assertEquals("五月天", dict.matchArtist("Mayday"))
        assertEquals("邓紫棋", dict.matchArtist("GEM"))
    }

    @Test
    fun `模糊包含匹配取最长命中`() {
        assertEquals("周杰伦", dict.matchArtist("周杰伦精选集"))
        assertEquals("林俊杰", dict.matchArtist("林俊杰歌曲大全"))
    }

    @Test
    fun `未命中返回 null`() {
        assertNull(dict.matchArtist("韩宝仪"))
        assertNull(dict.matchArtist(""))
        assertNull(dict.matchArtist("   "))
    }

    // ---------------- 导入 ----------------

    @Test
    fun `导入基本流程`() {
        val result = dict.importFromText("韩宝仪\n邓丽君\n", ArtistType.ARTIST)
        assertEquals(2, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(2, dict.userImportedCount())
        // 导入后可匹配
        assertEquals("韩宝仪", dict.matchArtist("韩宝仪"))
    }

    @Test
    fun `导入跳过空行与注释`() {
        val result = dict.importFromText(
            "# 评论\n\n韩宝仪\n   \n# 又一条注释\n邓丽君\n",
            ArtistType.ARTIST,
        )
        assertEquals(2, result.imported)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `导入 trim 首尾空格`() {
        val result = dict.importFromText("  韩宝仪  \n", ArtistType.ARTIST)
        assertEquals(1, result.imported)
        assertEquals("韩宝仪", dict.matchArtist("韩宝仪"))
    }

    @Test
    fun `导入文本内重复去重`() {
        val result = dict.importFromText("韩宝仪\n韩宝仪\n韩宝仪\n", ArtistType.ARTIST)
        assertEquals(1, result.imported)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `导入与 BUILTIN 重复自动跳过`() {
        val result = dict.importFromText("周杰伦\n韩宝仪\n", ArtistType.ARTIST)
        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        // 内置条目数不变
        assertEquals(ArtistSeedData.STANDARD.size + ArtistSeedData.ALIASES.size, dict.builtinCount())
    }

    @Test
    fun `重复导入同一文件全部跳过`() {
        dict.importFromText("韩宝仪\n邓丽君\n", ArtistType.ARTIST)
        val second = dict.importFromText("韩宝仪\n邓丽君\n", ArtistType.ARTIST)
        assertEquals(0, second.imported)
        assertEquals(2, second.skipped)
    }

    @Test
    fun `导入乐队类型`() {
        val result = dict.importFromText("草东没有派对\n", ArtistType.BAND)
        assertEquals(1, result.imported)
        val entry = dict.allUserImported().first { it.name == "草东没有派对" }
        assertEquals(ArtistType.BAND, entry.type)
    }

    @Test
    fun `中日英文混合导入无乱码`() {
        val result = dict.importFromText("韩宝仪\n中島みゆき\nGrimes\n", ArtistType.ARTIST)
        assertEquals(3, result.imported)
        assertEquals("韩宝仪", dict.matchArtist("韩宝仪"))
        assertEquals("中島みゆき", dict.matchArtist("中島みゆき"))
        assertEquals("Grimes", dict.matchArtist("grimes"))
    }

    @Test
    fun `空文本导入返回零`() {
        val result = dict.importFromText("", ArtistType.ARTIST)
        assertEquals(0, result.imported)
        assertEquals(0, result.skipped)
    }

    // ---------------- 删除 / 导出 ----------------

    @Test
    fun `删除用户导入条目`() {
        dict.importFromText("韩宝仪\n", ArtistType.ARTIST)
        val entry = dict.allUserImported().first { it.name == "韩宝仪" }
        assertTrue(dict.deleteUserImported(entry.id))
        assertEquals(0, dict.userImportedCount())
        assertNull(dict.matchArtist("韩宝仪"))
    }

    @Test
    fun `导出用户导入条目每行一个名字`() {
        dict.importFromText("韩宝仪\n邓丽君\n", ArtistType.ARTIST)
        val exported = dict.exportUserImported()
        val lines = exported.lines().filter { it.isNotBlank() }.toSet()
        assertEquals(setOf("韩宝仪", "邓丽君"), lines)
    }

    @Test
    fun `导出空列表为空字符串`() {
        assertEquals("", dict.exportUserImported())
    }

    @Test
    fun `内置条目不出现在用户列表`() {
        assertTrue(dict.allUserImported().isEmpty())
        dict.importFromText("韩宝仪\n", ArtistType.ARTIST)
        val all = dict.allUserImported()
        assertEquals(1, all.size)
        assertNotNull(all.first().importedAt)
    }
}
