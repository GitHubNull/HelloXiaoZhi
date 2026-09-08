package org.oxff.helloxiaozhi.data.db

/**
 * 内置歌手/乐队种子数据（自原 MusicMetadataInferrer.KNOWN_ARTISTS / ARTIST_ALIASES 迁移）。
 *
 * 一次性写入（RoomDatabase.Callback.onCreate），后续版本不自动更新。
 */
object ArtistSeedData {

    /** 标准名 -> 类型 */
    val STANDARD: List<Pair<String, ArtistType>> = buildList {
        // 华语男歌手 (25)
        listOf(
            "周杰伦", "林俊杰", "王力宏", "陶喆", "陈奕迅", "张学友",
            "刘德华", "周华健", "任贤齐", "张信哲", "林志炫", "光良",
            "潘玮柏", "罗志祥", "吴克群", "方大同", "萧敬腾", "林宥嘉",
            "杨宗纬", "许嵩", "汪苏泷", "薛之谦", "李荣浩", "毛不易", "华晨宇",
        ).forEach { add(it to ArtistType.ARTIST) }
        // 华语女歌手 (25)
        listOf(
            "孙燕姿", "蔡依林", "梁静茹", "张韶涵", "王心凌", "杨丞琳",
            "田馥甄", "范玮琪", "刘若英", "莫文蔚", "林忆莲", "王菲",
            "那英", "张惠妹", "李玟", "萧亚轩", "蔡健雅", "戴佩妮",
            "陈绮贞", "徐佳莹", "张靓颖", "周笔畅", "李宇春", "邓紫棋", "郁可唯",
        ).forEach { add(it to ArtistType.ARTIST) }
        // 华语乐队/组合 (15)
        listOf(
            "五月天", "苏打绿", "信乐团", "飞儿乐团", "S.H.E", "Twins",
            "Beyond", "小虎队", "动力火车", "羽泉", "水木年华",
            "新裤子", "痛仰乐队", "万能青年旅店", "逃跑计划",
        ).forEach { add(it to ArtistType.BAND) }
        // 日韩 (10)
        listOf(
            "滨崎步", "宇多田光", "仓木麻衣", "中岛美嘉", "安室奈美惠",
        ).forEach { add(it to ArtistType.ARTIST) }
        listOf(
            "东方神起", "Super Junior", "BIGBANG", "EXO", "BTS", "少女时代",
        ).forEach { add(it to ArtistType.BAND) }
        // 欧美歌手 (17)
        listOf(
            "Michael Jackson", "Madonna", "Britney Spears", "Beyoncé",
            "Taylor Swift", "Adele", "Ed Sheeran", "Bruno Mars",
            "Avril Lavigne", "Eminem", "Jay-Z", "Kanye West", "Rihanna",
            "Katy Perry", "Lady Gaga", "Justin Bieber", "Ariana Grande",
            "Billie Eilish", "Shawn Mendes",
        ).forEach { add(it to ArtistType.ARTIST) }
        // 欧美乐队/组合 (8)
        listOf(
            "Coldplay", "Linkin Park", "Maroon 5", "OneRepublic",
            "Backstreet Boys", "Westlife",
        ).forEach { add(it to ArtistType.BAND) }
    }

    /** 别名 -> 标准名 */
    val ALIASES: Map<String, String> = mapOf(
        "周董" to "周杰伦", "JAY" to "周杰伦", "Jay Chou" to "周杰伦",
        "JJ" to "林俊杰", "JJ Lin" to "林俊杰",
        "Eason" to "陈奕迅", "Eason Chan" to "陈奕迅",
        "Jolin" to "蔡依林", "Fish" to "梁静茹",
        "Stefanie" to "孙燕姿", "Angela" to "张韶涵",
        "Cyndi" to "王心凌", "Rainie" to "杨丞琳",
        "Hebe" to "田馥甄", "Faye" to "王菲",
        "A-Mei" to "张惠妹", "CoCo" to "李玟",
        "Elva" to "萧亚轩", "Tanya" to "蔡健雅",
        "Penny" to "戴佩妮", "Cheer" to "陈绮贞",
        "Lala" to "徐佳莹", "Jane" to "张靓颖",
        "Bibi" to "周笔畅", "Chris" to "李宇春",
        "GEM" to "邓紫棋", "G.E.M" to "邓紫棋",
        "Vae" to "许嵩", "Silence" to "汪苏泷",
        "Joker" to "薛之谦", "Ronghao" to "李荣浩",
        "Mayday" to "五月天", "Sodagreen" to "苏打绿",
        "Shin" to "信乐团", "F.I.R" to "飞儿乐团",
        "SHE" to "S.H.E", "TVXQ" to "东方神起",
        "SJ" to "Super Junior", "SNSD" to "少女时代",
        "MJ" to "Michael Jackson",
        "Britney" to "Britney Spears",
        "Taylor" to "Taylor Swift",
        "Avril" to "Avril Lavigne",
        "BSB" to "Backstreet Boys",
        "Gaga" to "Lady Gaga",
        "Ariana" to "Ariana Grande",
        "Billie" to "Billie Eilish",
    )

    /** 别名对应类型（默认 ARTIST，乐队别名在此覆盖） */
    private val BAND_ALIASES = setOf(
        "Mayday", "Sodagreen", "Shin", "F.I.R", "SHE", "TVXQ", "SJ", "SNSD", "BSB",
    )

    /** 生成标准名实体列表 */
    fun standardEntries(): List<ArtistDictEntry> = STANDARD.map { (name, type) ->
        ArtistDictEntry(
            name = name,
            nameLower = name.lowercase(),
            type = type,
            source = DictSource.BUILTIN,
            canonicalName = null,
            importedAt = null,
        )
    }

    /** 生成别名实体列表 */
    fun aliasEntries(): List<ArtistDictEntry> = ALIASES.map { (alias, canonical) ->
        ArtistDictEntry(
            name = alias,
            nameLower = alias.lowercase(),
            type = if (alias in BAND_ALIASES) ArtistType.BAND else ArtistType.ARTIST,
            source = DictSource.BUILTIN,
            canonicalName = canonical,
            importedAt = null,
        )
    }
}
