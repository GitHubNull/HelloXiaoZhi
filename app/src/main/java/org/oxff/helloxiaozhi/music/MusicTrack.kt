package org.oxff.helloxiaozhi.music

import org.oxff.helloxiaozhi.data.db.MetadataSource

/**
 * 音乐曲目数据模型。
 *
 * @param id 唯一标识（路径或 URI 的哈希）
 * @param title 曲目标题（从元数据提取，缺失时用文件名）
 * @param artist 歌手（从元数据/文件夹名/文件名推断，缺失时为 "未知歌手"）
 * @param album 专辑名（从元数据或文件夹名推断，可能为 null）
 * @param duration 时长（毫秒）
 * @param path 文件路径或 SAF URI 字符串
 * @param source 来源类型
 * @param genre 音乐类型（从元数据或文件夹名推断，可能为 null）
 * @param lastModified 文件最后修改时间（用于缓存失效判断）
 * @param metadataSource 元数据来源（ID3/文件夹名/描述文件/文件名模式）
 */
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long,
    val path: String,
    val source: MusicSource,
    val genre: String? = null,
    val lastModified: Long = 0L,
    val metadataSource: MetadataSource = MetadataSource.ID3,
)

/** 音乐来源类型 */
enum class MusicSource {
    /** 本地文件系统路径 */
    LOCAL,
    /** SAF 文档树 URI */
    SAF,
}
