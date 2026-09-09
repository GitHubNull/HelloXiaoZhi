package org.oxff.helloxiaozhi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MusicKeywordNormalizer 单元测试：口语化 STT 文本 → 曲库检索候选关键词。
 *
 * 纯 JVM 测试（归一化器无 Android 依赖）。
 */
class MusicKeywordNormalizerTest {

    // ---------------- 真机回归：口语长句 ----------------

    @Test
    fun `口语长句中提取被泛化词与填充词三重包裹的歌手名`() {
        // 真机实测（2026-09-09 logcat）：用户说 "你先帮我播放音乐吧，那个那个韩宝仪的音乐。"
        // 旧单串清洗提取出 "音乐吧，那个那个韩宝" → No tracks found，音乐从未播放。
        // 目标歌手名 "韩宝仪" 被句中泛化词、口头填充词与末尾装饰词三重包裹。
        val candidates = MusicKeywordNormalizer.candidates(
            "你先帮我播放音乐吧，那个那个韩宝仪的音乐。"
        )
        assertEquals("韩宝仪", candidates.first())
        assertTrue(candidates.contains("韩宝仪"))
    }

    @Test
    fun `口语长句候选中不含纯泛化词子句`() {
        // 子句 "你先帮我播放音乐吧" 清洗后为 "音乐"，属泛化词，
        // 不得进入候选（否则会用 "音乐" 去搜而误命中同名曲目）
        val candidates = MusicKeywordNormalizer.candidates(
            "你先帮我播放音乐吧，那个那个韩宝仪的音乐。"
        )
        assertFalse(candidates.contains("音乐"))
        assertFalse(candidates.contains("音乐吧"))
    }

    // ---------------- 基础播放指令（防回归） ----------------

    @Test
    fun `简单播放指令提取曲名`() {
        assertEquals("晴天", MusicKeywordNormalizer.primary("播放晴天"))
    }

    @Test
    fun `带歌曲后缀提取歌手名`() {
        assertEquals("韩宝仪", MusicKeywordNormalizer.primary("播放韩宝仪的歌曲"))
        assertEquals("韩宝仪", MusicKeywordNormalizer.primary("播放韩宝仪的歌"))
        assertEquals("韩宝仪", MusicKeywordNormalizer.primary("播放韩宝仪的音乐"))
    }

    @Test
    fun `末尾中文句号不影响装饰词剥离`() {
        // 回归：先剥标点再剥装饰词，否则 endsWith("的歌曲") 因结尾 "。" 失配
        assertEquals("韩宝仪", MusicKeywordNormalizer.primary("播放韩宝仪的歌曲。"))
    }

    @Test
    fun `礼貌与引导前缀被剥离`() {
        assertEquals("韩宝仪", MusicKeywordNormalizer.primary("给我播放韩宝仪的歌曲。"))
        assertEquals("韩宝仪", MusicKeywordNormalizer.primary("你再帮我播放一首韩宝仪的歌曲。"))
        assertEquals("韩宝仪", MusicKeywordNormalizer.primary("能不能帮我放一首韩宝仪的歌"))
    }

    @Test
    fun `开头量词被剥离`() {
        // 回归：旧实现提取出 "一首韩宝仪" 导致失配
        assertEquals("韩宝仪", MusicKeywordNormalizer.primary("播放一首韩宝仪的歌曲。"))
        assertEquals("晴天", MusicKeywordNormalizer.primary("来一首晴天"))
    }

    @Test
    fun `曲名自身含的地字不被装饰词剥离误伤`() {
        // 回归：《想要潇洒的离开》结尾不是装饰词，须完整保留
        assertEquals(
            "想要潇洒的离开",
            MusicKeywordNormalizer.primary("播放想要潇洒的离开。"),
        )
        assertEquals(
            "想要潇洒的离开",
            MusicKeywordNormalizer.primary("播放一首想要潇洒的离开。"),
        )
    }

    @Test
    fun `指示代词与语气词后缀被剥离`() {
        assertEquals("晴天", MusicKeywordNormalizer.primary("播放晴天这首歌"))
        assertEquals("晴天", MusicKeywordNormalizer.primary("播放晴天吧"))
        assertEquals("晴天", MusicKeywordNormalizer.primary("放一首晴天呗"))
    }

    // ---------------- 激进/保守双变体 ----------------

    @Test
    fun `填充词剥离误伤曲名时保守变体兜底`() {
        // 曲名本身以 "那个" 开头时，激进清洗会误剥；
        // 保守变体须保留完整曲名，让曲库反查有机会命中
        val candidates = MusicKeywordNormalizer.candidates("播放那个女孩对我说")
        assertEquals("女孩对我说", candidates.first())
        assertTrue(
            "保守变体须保留完整曲名，实际候选=$candidates",
            candidates.contains("那个女孩对我说"),
        )
    }

    @Test
    fun `重复口头语被整体剥离`() {
        val candidates = MusicKeywordNormalizer.candidates("播放那个那个晴天")
        assertEquals("晴天", candidates.first())
    }

    @Test
    fun `纯填充词子句不进候选`() {
        // 子句 "就是那个" 清洗后仍为填充词，若进候选会用无信息量的词
        // 去反查而误命中；同时验证口语播放动词 "放一下" 能被识别
        val candidates = MusicKeywordNormalizer.candidates("就是那个，帮我放一下晴天吧")
        assertEquals("晴天", candidates.first())
        assertFalse(candidates.contains("就是那个"))
        assertFalse(candidates.contains("那个"))
    }

    @Test
    fun `口语播放动词被识别`() {
        assertEquals("晴天", MusicKeywordNormalizer.primary("放一下晴天"))
        assertEquals("晴天", MusicKeywordNormalizer.primary("帮我放个晴天"))
        assertEquals("晴天", MusicKeywordNormalizer.primary("听一下晴天"))
    }

    // ---------------- 含分隔符的曲名兜底 ----------------

    @Test
    fun `曲名含逗号时整句候选作为末尾兜底`() {
        // 子句切分会把 "Hello, World" 拆散，须保留整句候选兜底
        val candidates = MusicKeywordNormalizer.candidates("播放Hello, World")
        assertTrue(candidates.contains("Hello"))
        assertTrue(candidates.contains("World"))
        assertEquals("Hello, World", candidates.last())
    }

    // ---------------- 边界与无效输入 ----------------

    @Test
    fun `纯泛化指令不产出候选`() {
        assertTrue(MusicKeywordNormalizer.candidates("播放音乐").isEmpty())
        assertTrue(MusicKeywordNormalizer.candidates("放首歌").isEmpty())
        assertTrue(MusicKeywordNormalizer.candidates("来点歌曲").isEmpty())
    }

    @Test
    fun `空输入与纯标点不产出候选`() {
        assertTrue(MusicKeywordNormalizer.candidates("").isEmpty())
        assertTrue(MusicKeywordNormalizer.candidates("   ").isEmpty())
        assertTrue(MusicKeywordNormalizer.candidates("。").isEmpty())
        assertTrue(MusicKeywordNormalizer.candidates("，，").isEmpty())
    }

    @Test
    fun `过短片段被过滤`() {
        // 单字关键词误命中率过高，不进候选
        assertTrue(MusicKeywordNormalizer.candidates("放").isEmpty())
        assertFalse(MusicKeywordNormalizer.candidates("播放晴天").contains("放"))
    }

    @Test
    fun `候选去重且保持优先级顺序`() {
        val candidates = MusicKeywordNormalizer.candidates("播放韩宝仪的歌曲")
        assertEquals(candidates.distinct(), candidates)
        assertEquals("韩宝仪", candidates.first())
    }

    @Test
    fun `英文曲名大小写原样保留由曲库负责匹配`() {
        assertEquals("Yesterday", MusicKeywordNormalizer.primary("播放Yesterday"))
    }
}
