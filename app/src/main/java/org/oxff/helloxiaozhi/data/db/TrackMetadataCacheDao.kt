package org.oxff.helloxiaozhi.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 曲目元数据缓存 DAO */
@Dao
interface TrackMetadataCacheDao {

    /** 批量写入/覆盖（扫描完成后） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(entries: List<TrackMetadataCache>)

    /** 按路径前缀清除（重新扫描某目录前） */
    @Query("DELETE FROM track_metadata_cache WHERE path LIKE :pathPrefix || '%'")
    fun deleteByPathPrefix(pathPrefix: String): Int

    /** 读取全部缓存（启动时恢复内存 tracks） */
    @Query("SELECT * FROM track_metadata_cache")
    fun all(): List<TrackMetadataCache>

    /** 缓存条目数 */
    @Query("SELECT COUNT(*) FROM track_metadata_cache")
    fun count(): Int

    /** 清空缓存 */
    @Query("DELETE FROM track_metadata_cache")
    fun clearAll(): Int
}
