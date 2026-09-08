package org.oxff.helloxiaozhi.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 歌手/乐队词典 DAO。
 *
 * 匹配策略与原 MusicMetadataInferrer 一致：精确 -> 别名 -> 忽略大小写 -> 模糊包含，
 * 但底层数据来源从内存 Set 切换为 SQL 索引查询。
 */
@Dao
interface ArtistDictDao {

    /** 精确匹配标准名（大小写不敏感，经 name_lower 索引） */
    @Query("SELECT * FROM artist_dict WHERE name_lower = :nameLower AND canonical_name IS NULL LIMIT 1")
    fun findExact(nameLower: String): ArtistDictEntry?

    /** 精确匹配别名（大小写不敏感），返回指向标准名的记录 */
    @Query("SELECT * FROM artist_dict WHERE name_lower = :nameLower AND canonical_name IS NOT NULL LIMIT 1")
    fun findAlias(nameLower: String): ArtistDictEntry?

    /**
     * 模糊包含匹配：候选名包含词典条目（如 "周杰伦精选集" 包含 "周杰伦"）。
     * 仅匹配标准名（canonical_name IS NULL），要求候选名长度大于条目长度避免短名误匹配。
     */
    @Query(
        "SELECT * FROM artist_dict WHERE canonical_name IS NULL " +
            "AND length(:candidateLower) > length(name_lower) " +
            "AND instr(:candidateLower, name_lower) > 0",
    )
    fun findContaining(candidateLower: String): List<ArtistDictEntry>

    /** 批量插入，UNIQUE(name) 冲突自动忽略（导入去重，含 BUILTIN 重复） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(entries: List<ArtistDictEntry>): List<Long>

    /** 全部用户导入条目（按导入时间倒序） */
    @Query("SELECT * FROM artist_dict WHERE source = 'USER_IMPORTED' ORDER BY imported_at DESC")
    fun allUserImported(): List<ArtistDictEntry>

    /** 用户导入条目数 */
    @Query("SELECT COUNT(*) FROM artist_dict WHERE source = 'USER_IMPORTED'")
    fun userImportedCount(): Int

    /** 内置条目数 */
    @Query("SELECT COUNT(*) FROM artist_dict WHERE source = 'BUILTIN'")
    fun builtinCount(): Int

    /** 删除单条用户导入记录 */
    @Query("DELETE FROM artist_dict WHERE id = :id AND source = 'USER_IMPORTED'")
    fun deleteUserImported(id: Long): Int

    /** 词典条目总数 */
    @Query("SELECT COUNT(*) FROM artist_dict")
    fun count(): Int
}
