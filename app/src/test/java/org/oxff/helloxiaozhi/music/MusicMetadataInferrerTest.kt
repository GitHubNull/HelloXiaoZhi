package org.oxff.helloxiaozhi.music

import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxff.helloxiaozhi.data.db.MetadataSource
import org.oxff.helloxiaozhi.data.db.MusicDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * MusicMetadataInferrer 单元测试：四级歌手推断、类型映射、专辑推断、
 * 描述文件规则优先级与文件夹名清洗。
 *
 * 词典来源切换为内存 Room 数据库（种子数据在 onCreate 写入）。
 */
@RunWith(RobolectricTestRunner::class)
class MusicMetadataInferrerTest {

    private lateinit var db: MusicDatabase
    private lateinit var inferrer: MusicMetadataInferrer

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MusicDatabase::class.java,
        ).allowMainThreadQueries().build()
        // inMemoryDatabaseBuilder 对 onCreate 回调支持不稳定，显式幂等写入种子数据
        MusicDatabase.seedIfEmpty(db)
        inferrer = MusicMetadataInferrer(ArtistDictionary(db.artistDictDao()))
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------- 歌手推断：第 1 级 ID3 ----------------

    @Test
    fun `ID3 歌手优先`() {
        val result = inferrer.infer("/music/周杰伦/晴天.mp3", "晴天.mp3", "李宗盛", null, null)
        assertEquals("李宗盛", result.artist)
        assertEquals(MetadataSource.ID3, result.source)
    }

    @Test
    fun `ID3 未知占位符不作为歌手`() {
        val result = inferrer.infer("/music/周杰伦/晴天.mp3", "晴天.mp3", "未知歌手", null, null)
        assertEquals("周杰伦", result.artist)
    }

    @Test
    fun `ID3 空白歌手回退文件夹推断`() {
        val result = inferrer.infer("/music/林俊杰/江南.mp3", "江南.mp3", "  ", null, null)
        assertEquals("林俊杰", result.artist)
    }

    // ---------------- 歌手推断：第 2 级 描述文件规则 ----------------

    @Test
    fun `描述文件规则覆盖文件夹名推断`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*.mp3"
              artist: 韩宝仪
              genre: 经典老歌
            """.trimIndent(),
        )
        // 文件夹名是"周杰伦"，但描述文件规则命中，优先使用规则
        val result = inferrer.infer("/music/周杰伦/舞女.mp3", "舞女.mp3", null, null, null, rules)
        assertEquals("韩宝仪", result.artist)
        assertEquals("经典老歌", result.genre)
        assertEquals(MetadataSource.DESCRIPTION_FILE, result.source)
    }

    @Test
    fun `描述文件规则不命中时回退文件夹推断`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "韩宝仪*"
              artist: 韩宝仪
            """.trimIndent(),
        )
        val result = inferrer.infer("/music/周杰伦/晴天.mp3", "晴天.mp3", null, null, null, rules)
        assertEquals("周杰伦", result.artist)
        assertEquals(MetadataSource.FOLDER_NAME, result.source)
    }

    @Test
    fun `描述文件规则提供专辑`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*"
              artist: 韩宝仪
              album: 舞女
            """.trimIndent(),
        )
        val result = inferrer.infer("/music/杂集/舞女.mp3", "舞女.mp3", null, null, null, rules)
        assertEquals("韩宝仪", result.artist)
        assertEquals("舞女", result.album)
    }

    @Test
    fun `ID3 优先于描述文件规则`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*"
              artist: 韩宝仪
            """.trimIndent(),
        )
        val result = inferrer.infer("/music/周杰伦/晴天.mp3", "晴天.mp3", "周杰伦", null, null, rules)
        assertEquals("周杰伦", result.artist)
        assertEquals(MetadataSource.ID3, result.source)
    }

    // ---------------- 歌手推断：第 3 级 文件夹名清洗 ----------------

    @Test
    fun `文件夹尾部数字被清洗`() {
        val result = inferrer.infer("/music/周杰伦2/晴天.mp3", "晴天.mp3", null, null, null)
        assertEquals("周杰伦", result.artist)
    }

    @Test
    fun `文件夹常见后缀被清洗`() {
        val result = inferrer.infer("/music/林俊杰-精选/江南.mp3", "江南.mp3", null, null, null)
        assertEquals("林俊杰", result.artist)
    }

    @Test
    fun `文件夹括号内容被清洗`() {
        val result = inferrer.infer("/music/王菲(精选)/红豆.mp3", "红豆.mp3", null, null, null)
        assertEquals("王菲", result.artist)
    }

    @Test
    fun `文件夹前缀被清洗`() {
        val result = inferrer.infer("/music/歌手周杰伦/晴天.mp3", "晴天.mp3", null, null, null)
        assertEquals("周杰伦", result.artist)
    }

    @Test
    fun `通用文件夹名跳过并回退文件名解析`() {
        val result = inferrer.infer("/music/歌曲/周杰伦 - 晴天.mp3", "周杰伦 - 晴天.mp3", null, null, null)
        assertEquals("周杰伦", result.artist)
    }

    @Test
    fun `词典未命中的文件夹名原样返回`() {
        val result = inferrer.infer("/music/张三自录/歌曲.mp3", "歌曲.mp3", null, null, null)
        assertEquals("张三自录", result.artist)
    }

    // ---------------- 歌手推断：第 3 级 词典匹配 ----------------

    @Test
    fun `词典精确匹配中英文歌手`() {
        assertEquals("周杰伦", inferrer.infer("/music/周杰伦/晴天.mp3", "晴天.mp3", null, null, null).artist)
        assertEquals(
            "Taylor Swift",
            inferrer.infer("/music/Taylor Swift/Love Story.mp3", "Love Story.mp3", null, null, null).artist,
        )
    }

    @Test
    fun `别名表映射到标准名`() {
        assertEquals("周杰伦", inferrer.infer("/music/周董/晴天.mp3", "晴天.mp3", null, null, null).artist)
        assertEquals("五月天", inferrer.infer("/music/Mayday/倔强.mp3", "倔强.mp3", null, null, null).artist)
        assertEquals("邓紫棋", inferrer.infer("/music/GEM/泡沫.mp3", "泡沫.mp3", null, null, null).artist)
    }

    @Test
    fun `忽略大小写匹配`() {
        assertEquals("Beyond", inferrer.infer("/music/beyond/海阔天空.mp3", "海阔天空.mp3", null, null, null).artist)
    }

    @Test
    fun `模糊匹配包含词典条目`() {
        val result = inferrer.infer("/music/周杰伦精选集/晴天.mp3", "晴天.mp3", null, null, null)
        assertEquals("周杰伦", result.artist)
    }

    // ---------------- 歌手推断：第 4 级 文件名模式 ----------------

    @Test
    fun `文件名方括号模式解析歌手`() {
        val result = inferrer.infer("/music/downloads/[周杰伦] 晴天.mp3", "[周杰伦] 晴天.mp3", null, null, null)
        // 父文件夹 downloads 非通用名，先按文件夹推断
        assertEquals("downloads", result.artist)
        val result2 = inferrer.infer("/music/[周杰伦] 晴天.mp3", "[周杰伦] 晴天.mp3", null, null, null)
        assertEquals("周杰伦", result2.artist)
    }

    @Test
    fun `文件名分隔符模式解析歌手`() {
        val result = inferrer.infer("/music/周杰伦 - 晴天.mp3", "周杰伦 - 晴天.mp3", null, null, null)
        assertEquals("周杰伦", result.artist)
        assertEquals(MetadataSource.PATTERN_MATCH, result.source)
    }

    @Test
    fun `文件名无法解析且无文件夹线索返回 null`() {
        val result = inferrer.infer("晴天.mp3", "晴天.mp3", null, null, null)
        assertNull(result.artist)
    }

    // ---------------- 类型推断 ----------------

    @Test
    fun `ID3 类型优先`() {
        val result = inferrer.infer("/music/轻音乐/歌曲.mp3", "歌曲.mp3", null, "爵士", null)
        assertEquals("爵士", result.genre)
    }

    @Test
    fun `文件夹关键词映射类型`() {
        assertEquals(
            "轻音乐",
            inferrer.infer("/music/轻音乐/歌曲.mp3", "歌曲.mp3", null, null, null).genre,
        )
        assertEquals(
            "摇滚",
            inferrer.infer("/music/Rock/歌曲.mp3", "歌曲.mp3", null, null, null).genre,
        )
        assertEquals(
            "说唱",
            inferrer.infer("/music/嘻哈/歌曲.mp3", "歌曲.mp3", null, null, null).genre,
        )
    }

    @Test
    fun `无类型线索返回 null`() {
        val result = inferrer.infer("/music/周杰伦/晴天.mp3", "晴天.mp3", null, null, null)
        assertNull(result.genre)
    }

    // ---------------- 专辑推断 ----------------

    @Test
    fun `ID3 专辑优先`() {
        val result = inferrer.infer("/music/周杰伦/范特西/晴天.mp3", "晴天.mp3", null, null, "叶惠美")
        assertEquals("叶惠美", result.album)
    }

    @Test
    fun `祖父文件夹作为专辑`() {
        // 三级结构：歌手目录/专辑目录/歌曲，歌手来自祖父目录，父目录作为专辑
        val result = inferrer.infer("/music/歌手/周杰伦/范特西/晴天.mp3", "晴天.mp3", null, null, null)
        assertEquals("周杰伦", result.artist)
        assertEquals("范特西", result.album)
    }

    @Test
    fun `祖父文件夹为通用名不作为专辑`() {
        val result = inferrer.infer("/music/周杰伦2/晴天.mp3", "晴天.mp3", null, null, null)
        assertNull(result.album)
    }

    @Test
    fun `未推断出歌手时不推断专辑`() {
        val result = inferrer.infer("/music/张三/晴天.mp3", "晴天.mp3", null, null, null)
        // 父文件夹"张三"作为歌手（词典未命中原样返回），祖父"music"为通用名
        assertEquals("张三", result.artist)
        assertNull(result.album)
    }

    @Test
    fun `祖父文件夹与歌手名相同不作为专辑`() {
        val result = inferrer.infer("/music/周杰伦/周杰伦/晴天.mp3", "晴天.mp3", null, null, null)
        assertEquals("周杰伦", result.artist)
        assertNull(result.album)
    }

    // ---------------- 路径兼容 ----------------

    @Test
    fun `Windows 反斜杠路径可解析`() {
        val result = inferrer.infer("E:\\music\\周杰伦\\晴天.mp3", "晴天.mp3", null, null, null)
        assertEquals("周杰伦", result.artist)
    }

    @Test
    fun `SAF 解码路径的目录名被清洗并命中导入词典`() {
        // 模拟 buildSafInferPath 解码出的相对路径（原 SAF URI 会把 /document/ 误当父目录）
        // 导入小众歌手韩宝仪；目录名“韩宝仪1”清洗去尾数后应命中
        ArtistDictionary(db.artistDictDao()).importFromText("韩宝仪", org.oxff.helloxiaozhi.data.db.ArtistType.ARTIST)
        val result = inferrer.infer("primary:free/韩宝仪1/昨夜梦醒时.mp3", "昨夜梦醒时.mp3", null, null, null)
        assertEquals("韩宝仪", result.artist)
    }
}
