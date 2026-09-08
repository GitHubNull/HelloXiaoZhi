package org.oxff.helloxiaozhi.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.oxff.helloxiaozhi.data.db.MetadataSource
import org.oxff.helloxiaozhi.data.db.MusicDatabase
import org.oxff.helloxiaozhi.data.db.TrackMetadataCache
import org.oxff.helloxiaozhi.music.MusicMetadataDescriptor.MetadataRule
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 音乐库管理器：扫描、索引、缓存音乐文件元数据。
 *
 * 支持两种音乐源：
 *  - 本地目录：通过 [File] 递归扫描
 *  - SAF 文档树：通过 [DocumentFile] 递归扫描
 *
 * 元数据缓存持久化到 `track_metadata_cache` 表（Room），避免每次启动全量扫描。
 * 歌手/乐队词典存于 `artist_dict` 表，经 [ArtistDictionary] 查询。
 * 扫描在 IO 线程执行，通过回调通知完成。
 */
open class MusicLibrary(
    private val context: Context,
    db: MusicDatabase,
) {

    /** 扫描完成回调（主线程） */
    var onScanComplete: ((trackCount: Int) -> Unit)? = null

    /** 扫描进度回调（主线程） */
    var onScanProgress: ((current: Int, total: Int) -> Unit)? = null

    /** 扫描状态变更回调（主线程） */
    var onScanStateChanged: ((scanning: Boolean) -> Unit)? = null

    private val artistDictionary = ArtistDictionary(db.artistDictDao())
    private val cacheDao = db.trackMetadataCacheDao()
    private val inferrer = MusicMetadataInferrer(artistDictionary)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private val scanProgress = AtomicInteger(0)
    private val scanTotal = AtomicInteger(0)

    /** 当前音乐库中的所有曲目 */
    @Volatile
    private var tracks: List<MusicTrack> = emptyList()

    /** 是否正在扫描 */
    val isScanning: Boolean get() = scanning.get()

    /** 词典仓库（供 UI 管理歌手/乐队词典） */
    val dictionary: ArtistDictionary get() = artistDictionary

    /**
     * 启动时从 DB 缓存异步恢复内存 tracks（避免每次启动全量扫描）。
     *
     * 由生产调用点（XiaoZhiController 构建后）显式调用，而非放在 [init] 中——
     * 分离副作用使单元测试可安全构造实例而不触发缓存恢复。
     */
    fun restoreCacheAsync() {
        executor.execute { loadCache() }
    }

    // ---------------- 扫描 ----------------

    /**
     * 扫描音乐库（本地目录 + SAF 文档树）。
     *
     * @param localPath 本地目录路径（空跳过）
     * @param safUri SAF 文档树 URI（空跳过）
     */
    fun scan(localPath: String, safUri: String) {
        if (scanning.getAndSet(true)) {
            Log.w(TAG, "Scan already in progress")
            return
        }
        scanProgress.set(0)
        scanTotal.set(0)
        notifyScanStateChanged(true)

        executor.execute {
            try {
                val allTracks = mutableListOf<MusicTrack>()

                // 扫描本地目录（设置页目录选择返回的是 SAF content URI，
                // 需走 DocumentFile 扫描；真实文件路径才走 File 扫描）
                if (localPath.isNotEmpty()) {
                    val localTracks = if (localPath.startsWith("content://")) {
                        scanSafDirectory(localPath)
                    } else {
                        scanLocalDirectory(localPath)
                    }
                    allTracks.addAll(localTracks)
                }

                // 扫描 SAF 文档树
                if (safUri.isNotEmpty()) {
                    val safTracks = scanSafDirectory(safUri)
                    allTracks.addAll(safTracks)
                }

                // 去重（按路径）
                tracks = allTracks.distinctBy { it.path }

                // 保存缓存到 DB
                saveCache()

                Log.i(TAG, "Scan complete: ${tracks.size} tracks")
                notifyScanComplete(tracks.size)
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                notifyScanComplete(0)
            } finally {
                scanning.set(false)
                notifyScanStateChanged(false)
            }
        }
    }

    /**
     * 扫描本地目录
     *
     * 同时收集各目录的 `.music_metadata.txt` 描述文件规则，供元数据推断优先命中。
     */
    private fun scanLocalDirectory(path: String): List<MusicTrack> {
        val result = mutableListOf<MusicTrack>()
        val root = File(path)
        if (!root.exists() || !root.isDirectory) {
            Log.w(TAG, "Local path not exists or not a directory: $path")
            return result
        }

        // 重新扫描前清除该目录的旧缓存
        cacheDao.deleteByPathPrefix(root.absolutePath)

        // 收集目录树中的描述文件规则（key=目录绝对路径）
        val descriptorRules = collectDescriptorRules(root)

        val files = root.walkTopDown().filter { it.isFile && isAudioFile(it.name) }.toList()
        scanTotal.addAndGet(files.size)

        for ((index, file) in files.withIndex()) {
            try {
                val rules = file.parentFile?.let { descriptorRules[it.absolutePath] }
                val track = extractMetadata(file.absolutePath, MusicSource.LOCAL, file.lastModified(), rules)
                if (track != null) {
                    result.add(track)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract metadata: ${file.name}", e)
            }
            scanProgress.set(index + 1)
            notifyScanProgress(index + 1, files.size)
        }

        Log.i(TAG, "Local scan: ${result.size} tracks from $path")
        return result
    }

    /**
     * 递归收集目录树中的 `.music_metadata.txt` 描述文件规则。
     *
     * @return key=目录绝对路径，value=该目录的规则列表
     */
    private fun collectDescriptorRules(root: File): Map<String, List<MetadataRule>> {
        val map = mutableMapOf<String, List<MetadataRule>>()
        root.walkTopDown().filter { it.isDirectory }.forEach { dir ->
            val descriptorFile = File(dir, MusicMetadataDescriptor.FILE_NAME)
            if (descriptorFile.exists() && descriptorFile.isFile) {
                try {
                    val rules = MusicMetadataDescriptor.parse(descriptorFile.readText(Charsets.UTF_8))
                    if (rules.isNotEmpty()) {
                        map[dir.absolutePath] = rules
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse descriptor: ${descriptorFile.absolutePath}", e)
                }
            }
        }
        return map
    }

    /**
     * 扫描 SAF 文档树
     */
    private fun scanSafDirectory(uriString: String): List<MusicTrack> {
        val result = mutableListOf<MusicTrack>()
        val treeUri = Uri.parse(uriString)
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.isDirectory) {
            Log.w(TAG, "SAF URI not accessible: $uriString")
            return result
        }

        // 先统计总数
        val allFiles = mutableListOf<DocumentFile>()
        collectAudioFiles(root, allFiles)
        scanTotal.addAndGet(allFiles.size)

        for ((index, docFile) in allFiles.withIndex()) {
            try {
                val track = extractMetadataFromSaf(docFile)
                if (track != null) {
                    result.add(track)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract metadata from SAF: ${docFile.name}", e)
            }
            scanProgress.set(scanProgress.get() + 1)
            notifyScanProgress(scanProgress.get(), scanTotal.get())
        }

        Log.i(TAG, "SAF scan: ${result.size} tracks from $uriString")
        return result
    }

    /**
     * 递归收集 SAF 音频文件
     */
    private fun collectAudioFiles(dir: DocumentFile, result: MutableList<DocumentFile>) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                collectAudioFiles(file, result)
            } else if (file.isFile && isAudioFile(file.name ?: "")) {
                result.add(file)
            }
        }
    }

    // ---------------- 元数据提取 ----------------

    /**
     * 从本地文件提取元数据。
     *
     * ID3 标签优先，缺失时由 [MusicMetadataInferrer] 从描述文件/文件夹名/文件名/词典推断补全。
     */
    private fun extractMetadata(
        path: String,
        source: MusicSource,
        lastModified: Long,
        descriptorRules: List<MetadataRule>?,
    ): MusicTrack? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val id3Title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val id3Artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val id3Album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val id3Genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L

            val fileName = File(path).name
            val inferred = inferrer.infer(path, fileName, id3Artist, id3Genre, id3Album, descriptorRules)

            MusicTrack(
                id = path.hashCode().toString(),
                title = id3Title ?: File(path).nameWithoutExtension,
                artist = inferred.artist ?: id3Artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST,
                album = inferred.album,
                duration = duration,
                path = path,
                source = source,
                genre = inferred.genre,
                lastModified = lastModified,
                metadataSource = inferred.source,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata: $path", e)
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 从 SAF [DocumentFile] URI 构造用于元数据推断的路径线索。
     *
     * SAF URI 的目录层次被编码在 documentId 中（`%2F` 转义斜杠），
     * 直接使用 uri.toString() 会让 [/extractParentDirName] 把 `/document/` 段误当父目录名。
     * 这里解码出真实相对路径（`primary:free/韩宝仪1/歌曲.mp3`），
     * 使文件夹名推断/词典匹配能正常工作；解码失败则回退到文件名。
     */
    private fun buildSafInferPath(uri: Uri, fallbackName: String): String {
        // [DocumentsContract.getDocumentId] 仅接受 /document/<docId> 形式；
        // ExternalStorageProvider 返回的是 /tree/<root>/document/<docId>（首段为 "tree"），
        // 会抛 IllegalArgumentException，故回退取最后一个 path segment（编码的完整 docId）。
        val rawDocId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            uri.lastPathSegment
        } ?: fallbackName
        return Uri.decode(rawDocId).ifBlank { fallbackName }
    }

    /**
     * 从 SAF 文档提取元数据。
     *
     * ID3 标签优先，缺失时由 [MusicMetadataInferrer] 从显示路径/文件名/词典推断补全。
     */
    private fun extractMetadataFromSaf(docFile: DocumentFile): MusicTrack? {
        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(docFile.uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val id3Title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val id3Artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val id3Album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val id3Genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = durationStr?.toLongOrNull() ?: 0L

                val displayName = docFile.name ?: ""
                // content:// URI 不含真实目录层级，直接推断会把 /document/ 段误当父目录名（得到 "document"）。
                // 改用 documentId 解码出相对路径（如 "primary:free/韩宝仪1/歌曲.mp3"）作为推断线索。
                val inferPath = buildSafInferPath(docFile.uri, displayName)
                val inferred = inferrer.infer(inferPath, displayName, id3Artist, id3Genre, id3Album, null)

                MusicTrack(
                    id = docFile.uri.toString().hashCode().toString(),
                    title = id3Title ?: displayName.substringBeforeLast('.').ifEmpty { "未知标题" },
                    artist = inferred.artist ?: id3Artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST,
                    album = inferred.album,
                    duration = duration,
                    path = docFile.uri.toString(),
                    source = MusicSource.SAF,
                    genre = inferred.genre,
                    lastModified = docFile.lastModified(),
                    metadataSource = inferred.source,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata from SAF: ${docFile.name}", e)
            null
        } finally {
            retriever.release()
        }
    }

    // ---------------- 查询 ----------------

    /** 获取所有曲目 */
    open fun allTracks(): List<MusicTrack> = tracks.toList()

    /** 按标题搜索（模糊匹配） */
    open fun searchByTitle(keyword: String): List<MusicTrack> {
        val lower = keyword.lowercase()
        return tracks.filter { it.title.lowercase().contains(lower) }
    }

    /** 按歌手搜索（模糊匹配） */
    open fun searchByArtist(keyword: String): List<MusicTrack> {
        val lower = keyword.lowercase()
        return tracks.filter { it.artist.lowercase().contains(lower) }
    }

    /** 按标题或歌手搜索 */
    open fun search(keyword: String): List<MusicTrack> {
        val lower = keyword.lowercase()
        return tracks.filter {
            it.title.lowercase().contains(lower) ||
                it.artist.lowercase().contains(lower) ||
                (it.album?.lowercase()?.contains(lower) == true)
        }
    }

    /** 去重排序的歌手列表 */
    open fun allArtists(): List<String> =
        tracks.map { it.artist }.distinct().sorted()

    /** 去重排序的类型列表 */
    open fun allGenres(): List<String> =
        tracks.mapNotNull { it.genre }.distinct().sorted()

    /** 去重排序的专辑列表 */
    open fun allAlbums(): List<String> =
        tracks.mapNotNull { it.album }.distinct().sorted()

    /** 按歌手筛选曲目 */
    open fun tracksByArtist(artist: String): List<MusicTrack> {
        val lower = artist.lowercase()
        return tracks.filter { it.artist.lowercase().contains(lower) }
    }

    /** 按专辑搜索曲目 */
    open fun searchByAlbum(album: String): List<MusicTrack> {
        val lower = album.lowercase()
        return tracks.filter { it.album?.lowercase()?.contains(lower) == true }
    }

    /** 按歌手统计曲目数 */
    open fun artistTrackCounts(): Map<String, Int> =
        tracks.groupingBy { it.artist }.eachCount()

    /** 按类型统计曲目数 */
    open fun genreTrackCounts(): Map<String, Int> =
        tracks.filter { it.genre != null }.groupingBy { it.genre!! }.eachCount()

    /** 随机返回一首 */
    open fun randomTrack(): MusicTrack? = tracks.randomOrNull()

    /** 按类型随机返回一首 */
    open fun randomByGenre(genre: String): MusicTrack? {
        val lower = genre.lowercase()
        val matched = tracks.filter { it.genre?.lowercase()?.contains(lower) == true }
        return matched.randomOrNull() ?: tracks.randomOrNull()
    }

    /** 获取曲目数量 */
    open fun trackCount(): Int = tracks.size

    // ---------------- 缓存 ----------------

    /**
     * 启动时从 DB 缓存恢复内存 tracks（避免每次启动全量扫描）。
     * 在 executor（IO 线程）中执行。
     */
    private fun loadCache() {
        try {
            val cached = cacheDao.all()
            tracks = cached.map { it.toMusicTrack() }
            Log.i(TAG, "Loaded ${cached.size} tracks from cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache", e)
        }
    }

    /**
     * 扫描完成后批量写入 DB 缓存（单事务）
     */
    private fun saveCache() {
        try {
            val entries = tracks.map { it.toCacheEntry() }
            cacheDao.upsertAll(entries)
            Log.i(TAG, "Saved ${entries.size} tracks to cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache", e)
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        tracks = emptyList()
        try {
            cacheDao.clearAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
        Log.i(TAG, "Cache cleared")
    }

    // ---------------- 实体映射 ----------------

    private fun TrackMetadataCache.toMusicTrack(): MusicTrack = MusicTrack(
        id = trackId,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        path = path,
        source = if (path.startsWith("content://")) MusicSource.SAF else MusicSource.LOCAL,
        genre = genre,
        lastModified = lastModified,
        metadataSource = metadataSource,
    )

    private fun MusicTrack.toCacheEntry(): TrackMetadataCache = TrackMetadataCache(
        trackId = id,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        duration = duration,
        path = path,
        lastModified = lastModified,
        metadataSource = metadataSource,
    )

    // ---------------- 工具方法 ----------------

    /**
     * 判断是否为音频文件（按扩展名）
     */
    private fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return AUDIO_EXTENSIONS.any { lower.endsWith(it) }
    }

    // ---------------- 回调通知（主线程） ----------------

    private fun notifyScanComplete(trackCount: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onScanComplete?.invoke(trackCount)
        }
    }

    private fun notifyScanProgress(current: Int, total: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onScanProgress?.invoke(current, total)
        }
    }

    private fun notifyScanStateChanged(scanning: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onScanStateChanged?.invoke(scanning)
        }
    }

    /**
     * 释放资源
     */
    fun shutdown() {
        executor.shutdown()
    }

    companion object {
        private const val TAG = "MusicLibrary"

        /** 歌手缺失时的兜底占位符 */
        const val UNKNOWN_ARTIST = "未知歌手"

        /** 支持的音频文件扩展名 */
        private val AUDIO_EXTENSIONS = listOf(
            ".mp3", ".wav", ".aac", ".flac", ".ogg", ".m4a", ".wma", ".opus"
        )
    }
}
