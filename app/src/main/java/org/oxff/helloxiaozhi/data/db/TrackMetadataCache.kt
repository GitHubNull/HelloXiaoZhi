package org.oxff.helloxiaozhi.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 元数据来源 */
enum class MetadataSource {
    /** ID3 标签 */
    ID3,

    /** 文件夹名推断 */
    FOLDER_NAME,

    /** .music_metadata.txt 描述文件 */
    DESCRIPTION_FILE,

    /** 文件名模式解析 */
    PATTERN_MATCH,
}

/**
 * 曲目元数据缓存（查询加速层）。
 *
 * 定位：MusicLibrary 内存 tracks 列表仍是运行时主数据源，本表仅作为
 * 启动时快速恢复与避免重复解析的加速层，扫描时批量写入。
 */
@Entity(tableName = "track_metadata_cache")
data class TrackMetadataCache(
    /** 曲目唯一标识（路径或 URI 的哈希） */
    @PrimaryKey
    val trackId: String,

    val title: String,
    val artist: String,
    val album: String?,
    val genre: String?,
    val duration: Long,
    val path: String,

    @ColumnInfo(name = "last_modified")
    val lastModified: Long,

    @ColumnInfo(name = "metadata_source")
    val metadataSource: MetadataSource,
)
