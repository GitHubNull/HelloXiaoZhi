package org.oxff.helloxiaozhi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MusicMetadataDescriptor 单元测试（纯 JVM）。
 *
 * 覆盖：YAML 子集解析（注释/空行/引号/缺字段/未知字段）、
 * glob 匹配（`*` / `?` / 大小写不敏感 / 扩展名剥离）。
 */
class MusicMetadataDescriptorTest {

    // ---------------- 解析 ----------------

    @Test
    fun `解析基本规则`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*.mp3"
              artist: 韩宝仪
              genre: 经典老歌
            """.trimIndent(),
        )
        assertEquals(1, rules.size)
        val rule = rules[0]
        assertEquals("*.mp3", rule.pattern)
        assertEquals("韩宝仪", rule.artist)
        assertEquals("经典老歌", rule.genre)
        assertNull(rule.album)
    }

    @Test
    fun `解析多条规则`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*.mp3"
              artist: 韩宝仪
            - pattern: "邓丽君*"
              artist: 邓丽君
              genre: 民谣
              album: 甜蜜蜜
            """.trimIndent(),
        )
        assertEquals(2, rules.size)
        assertEquals("韩宝仪", rules[0].artist)
        assertEquals("邓丽君", rules[1].artist)
        assertEquals("甜蜜蜜", rules[1].album)
    }

    @Test
    fun `注释与空行被忽略`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            # 这是注释

            - pattern: "*.mp3"
              # 规则内注释
              artist: 韩宝仪

            """.trimIndent(),
        )
        assertEquals(1, rules.size)
        assertEquals("韩宝仪", rules[0].artist)
    }

    @Test
    fun `双引号包裹的值被剥离`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*.mp3"
              artist: "韩宝仪"
            """.trimIndent(),
        )
        assertEquals("韩宝仪", rules[0].artist)
    }

    @Test
    fun `无引号值原样保留`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*.mp3"
              artist: 韩宝仪
            """.trimIndent(),
        )
        assertEquals("韩宝仪", rules[0].artist)
    }

    @Test
    fun `缺少 pattern 的规则被丢弃`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - artist: 韩宝仪
              genre: 经典老歌
            """.trimIndent(),
        )
        assertTrue(rules.isEmpty())
    }

    @Test
    fun `未知字段被忽略`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*.mp3"
              artist: 韩宝仪
              year: 1990
              comment: 测试
            """.trimIndent(),
        )
        assertEquals(1, rules.size)
        assertEquals("韩宝仪", rules[0].artist)
    }

    @Test
    fun `规则外孤立字段被忽略`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            artist: 韩宝仪
            - pattern: "*.mp3"
              artist: 邓丽君
            """.trimIndent(),
        )
        assertEquals(1, rules.size)
        assertEquals("邓丽君", rules[0].artist)
    }

    @Test
    fun `空文本解析为空列表`() {
        assertTrue(MusicMetadataDescriptor.parse("").isEmpty())
        assertTrue(MusicMetadataDescriptor.parse("# 只有注释\n\n").isEmpty())
    }

    @Test
    fun `字段名大小写不敏感`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - Pattern: "*.mp3"
              Artist: 韩宝仪
            """.trimIndent(),
        )
        assertEquals(1, rules.size)
        assertEquals("*.mp3", rules[0].pattern)
        assertEquals("韩宝仪", rules[0].artist)
    }

    // ---------------- glob 匹配 ----------------

    @Test
    fun `星号匹配任意字符序列`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*.mp3"
              artist: 韩宝仪
            """.trimIndent(),
        )
        val hit = MusicMetadataDescriptor.match(rules, "往事只能回味.mp3")
        assertEquals("韩宝仪", hit?.artist)
    }

    @Test
    fun `星号匹配去扩展名后的文件名`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "邓丽君*"
              artist: 邓丽君
            """.trimIndent(),
        )
        // 文件名（去扩展名）以"邓丽君"开头
        assertEquals("邓丽君", MusicMetadataDescriptor.match(rules, "邓丽君 - 甜蜜蜜.mp3")?.artist)
        // 全名也能匹配（含扩展名）
        assertEquals("邓丽君", MusicMetadataDescriptor.match(rules, "邓丽君全集01.mp3")?.artist)
    }

    @Test
    fun `问号匹配单字符`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "song?.mp3"
              artist: 测试
            """.trimIndent(),
        )
        assertEquals("测试", MusicMetadataDescriptor.match(rules, "song1.mp3")?.artist)
        assertNull(MusicMetadataDescriptor.match(rules, "song12.mp3"))
    }

    @Test
    fun `匹配大小写不敏感`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "*.MP3"
              artist: 测试
            """.trimIndent(),
        )
        assertEquals("测试", MusicMetadataDescriptor.match(rules, "song.mp3")?.artist)
    }

    @Test
    fun `第一条命中的规则胜出`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "韩宝仪*"
              artist: 韩宝仪
            - pattern: "*"
              artist: 未知
            """.trimIndent(),
        )
        assertEquals("韩宝仪", MusicMetadataDescriptor.match(rules, "韩宝仪 - 舞女.mp3")?.artist)
        assertEquals("未知", MusicMetadataDescriptor.match(rules, "其他 - 歌曲.mp3")?.artist)
    }

    @Test
    fun `未命中返回 null`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "韩宝仪*"
              artist: 韩宝仪
            """.trimIndent(),
        )
        assertNull(MusicMetadataDescriptor.match(rules, "邓丽君 - 甜蜜蜜.mp3"))
    }

    @Test
    fun `空规则列表返回 null`() {
        assertNull(MusicMetadataDescriptor.match(emptyList(), "song.mp3"))
    }

    @Test
    fun `正则特殊字符被转义`() {
        val rules = MusicMetadataDescriptor.parse(
            """
            - pattern: "song (live).mp3"
              artist: 测试
            """.trimIndent(),
        )
        assertEquals("测试", MusicMetadataDescriptor.match(rules, "song (live).mp3")?.artist)
        assertNull(MusicMetadataDescriptor.match(rules, "song live.mp3"))
    }
}
