package org.oxff.helloxiaozhi.music

import org.oxff.helloxiaozhi.data.db.ArtistDictDao
import org.oxff.helloxiaozhi.data.db.ArtistDictEntry
import org.oxff.helloxiaozhi.data.db.ArtistType
import org.oxff.helloxiaozhi.data.db.DictSource

/**
 * 歌手/乐队词典仓库：封装 [ArtistDictDao]，对外提供匹配与导入管理接口。
 *
 * 匹配策略与原 MusicMetadataInferrer 一致：精确 -> 别名 -> 忽略大小写 -> 模糊包含，
 * 但底层数据来源从内存 Set 切换为 SQL 索引查询。
 */
class ArtistDictionary(private val dao: ArtistDictDao) {

    /** 导入结果 */
    data class ImportResult(
        val imported: Int,
        val skipped: Int,
    )

    /**
     * 将候选歌手名与词典匹配。
     * 优先级：精确匹配标准名 -> 别名映射 -> 模糊包含匹配。
     *
     * @return 命中返回标准名，未命中返回 null
     */
    fun matchArtist(candidate: String): String? {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()

        // 精确匹配标准名
        dao.findExact(lower)?.let { return it.name }

        // 精确匹配别名 -> 返回标准名
        dao.findAlias(lower)?.canonicalName?.let { return it }

        // 模糊包含匹配：候选名包含词典标准名（如 "周杰伦精选集" 包含 "周杰伦"）
        val containing = dao.findContaining(lower)
        if (containing.isNotEmpty()) {
            // 多个命中时取最长（最具体）的标准名，减少短名误匹配
            return containing.maxByOrNull { it.name.length }?.name
        }

        return null
    }

    /**
     * 从文本批量导入。
     *
     * 格式：每行一个名字，空行与 `#` 注释行忽略，首尾空格 trim。
     * 去重：name 已存在（UNIQUE 索引 + IGNORE）自动跳过，含 BUILTIN 重复。
     *
     * @param text UTF-8 文本内容
     * @param type 歌手 / 乐队
     */
    fun importFromText(text: String, type: ArtistType): ImportResult {
        val now = System.currentTimeMillis()
        val entries = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .distinct()
            .map { name ->
                ArtistDictEntry(
                    name = name,
                    nameLower = name.lowercase(),
                    type = type,
                    source = DictSource.USER_IMPORTED,
                    canonicalName = null,
                    importedAt = now,
                )
            }
            .toList()

        if (entries.isEmpty()) return ImportResult(imported = 0, skipped = 0)

        val results = dao.insertAll(entries)
        // insertAll 返回每条的 rowId，冲突忽略时为 -1
        val imported = results.count { it != -1L }
        return ImportResult(imported = imported, skipped = entries.size - imported)
    }

    /** 全部用户导入条目（按导入时间倒序） */
    fun allUserImported(): List<ArtistDictEntry> = dao.allUserImported()

    /** 用户导入条目数 */
    fun userImportedCount(): Int = dao.userImportedCount()

    /** 内置条目数 */
    fun builtinCount(): Int = dao.builtinCount()

    /** 删除单条用户导入记录 */
    fun deleteUserImported(id: Long): Boolean = dao.deleteUserImported(id) > 0

    /** 导出用户导入条目为文本（每行一个名字） */
    fun exportUserImported(): String =
        dao.allUserImported().joinToString("\n") { it.name }
}
