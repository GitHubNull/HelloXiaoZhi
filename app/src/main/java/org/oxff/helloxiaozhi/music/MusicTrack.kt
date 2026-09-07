package org.oxff.helloxiaozhi.music

/**
 * 音乐曲目数据模型。
 *
 * @param id 唯一标识（路径或 URI 的哈希）
 * @param title 曲目标题（从元数据提取，缺失时用文件名）
 * @param artist 歌手（从元数据提取，缺失时为 "未知歌手"）
 * @param duration 时长（毫秒）
 * @param path 文件路径或 SAF URI 字符串
 * @param source 来源类型
 * @param genre 音乐类型（从元数据提取，可能为 null）
 * @param lastModified 文件最后修改时间（用于缓存失效判断）
 */
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val path: String,
    val source: MusicSource,
    val genre: String? = null,
    val lastModified: Long = 0L,
)

/** 音乐来源类型 */
enum class MusicSource {
    /** 本地文件系统路径 */
    LOCAL,
    /** SAF 文档树 URI */
    SAF,
}
