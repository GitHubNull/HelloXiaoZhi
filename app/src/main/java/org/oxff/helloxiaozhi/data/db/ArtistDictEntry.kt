package org.oxff.helloxiaozhi.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 词典条目类型：歌手 / 乐队 */
enum class ArtistType {
    ARTIST,
    BAND,
}

/** 词典条目来源：内置种子 / 用户导入 */
enum class DictSource {
    BUILTIN,
    USER_IMPORTED,
}

/**
 * 歌手/乐队词典条目。
 *
 * 单表设计：用 [type] 区分歌手与乐队，避免匹配查询时 UNION 两表。
 * [nameLower] 冗余存储 name 的小写形式，替代 Room 支持有限的 LOWER() 函数索引，
 * 插入/更新时由代码保证一致性。
 *
 * 别名机制：别名单独成一条记录，[canonicalName] 指向标准名；标准名本身 [canonicalName] 为 null。
 */
@Entity(
    tableName = "artist_dict",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["name_lower"]),
    ],
)
data class ArtistDictEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 名称（标准名或别名） */
    val name: String,

    /** name 的小写形式（冗余列，用于大小写不敏感匹配） */
    @ColumnInfo(name = "name_lower")
    val nameLower: String,

    /** 歌手 / 乐队 */
    val type: ArtistType,

    /** 内置 / 用户导入 */
    val source: DictSource,

    /** 别名指向的标准名；标准名本身为 null */
    @ColumnInfo(name = "canonical_name")
    val canonicalName: String? = null,

    /** 导入时间戳（仅 USER_IMPORTED 有值） */
    @ColumnInfo(name = "imported_at")
    val importedAt: Long? = null,
)
