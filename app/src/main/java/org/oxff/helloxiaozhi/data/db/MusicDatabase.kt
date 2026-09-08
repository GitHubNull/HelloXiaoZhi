package org.oxff.helloxiaozhi.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 音乐元数据库：歌手/乐队词典 + 曲目元数据缓存。
 *
 * 无向后兼容要求：版本升级直接 destructive migration。
 * 内置种子数据在 [Callback.onCreate] 一次性写入。
 */
@Database(
    entities = [ArtistDictEntry::class, TrackMetadataCache::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(MusicConverters::class)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun artistDictDao(): ArtistDictDao
    abstract fun trackMetadataCacheDao(): TrackMetadataCacheDao

    companion object {
        private const val DB_NAME = "music_metadata.db"

        @Volatile
        private var instance: MusicDatabase? = null

        fun getInstance(context: Context): MusicDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }
        }

        private fun build(context: Context): MusicDatabase {
            return Room.databaseBuilder(context.applicationContext, MusicDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        seedArtists(db)
                    }
                })
                .build()
        }

        /** 批量写入内置种子数据（标准名 + 别名），单事务提交 */
        private fun seedArtists(db: SupportSQLiteDatabase) {
            val entries = ArtistSeedData.standardEntries() + ArtistSeedData.aliasEntries()
            db.beginTransaction()
            try {
                for (e in entries) {
                    val values = ContentValues().apply {
                        put("name", e.name)
                        put("name_lower", e.nameLower)
                        put("type", e.type.name)
                        put("source", e.source.name)
                        put("canonical_name", e.canonicalName)
                        put("imported_at", e.importedAt)
                    }
                    db.insert("artist_dict", SQLiteDatabase.CONFLICT_IGNORE, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        /**
         * 显式写入种子数据（幂等，UNIQUE+IGNORE）。
         *
         * 生产环境由 [Callback.onCreate] 自动触发；测试（inMemoryDatabaseBuilder）
         * 对 onCreate 回调支持不稳定，提供此入口供测试 setUp 直接调用。
         */
        fun seedIfEmpty(db: MusicDatabase) {
            if (db.artistDictDao().count() == 0) {
                seedArtists(db.openHelper.writableDatabase)
            }
        }
    }
}
