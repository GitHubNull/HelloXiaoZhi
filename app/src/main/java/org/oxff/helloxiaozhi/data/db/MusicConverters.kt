package org.oxff.helloxiaozhi.data.db

import androidx.room.TypeConverter

/** Room 枚举 <-> String 转换器 */
class MusicConverters {

    @TypeConverter
    fun fromArtistType(value: ArtistType): String = value.name

    @TypeConverter
    fun toArtistType(value: String): ArtistType = ArtistType.valueOf(value)

    @TypeConverter
    fun fromDictSource(value: DictSource): String = value.name

    @TypeConverter
    fun toDictSource(value: String): DictSource = DictSource.valueOf(value)

    @TypeConverter
    fun fromMetadataSource(value: MetadataSource): String = value.name

    @TypeConverter
    fun toMetadataSource(value: String): MetadataSource = MetadataSource.valueOf(value)
}
