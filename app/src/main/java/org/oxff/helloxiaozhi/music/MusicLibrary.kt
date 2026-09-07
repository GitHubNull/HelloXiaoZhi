package org.oxff.helloxiaozhi.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
 * 元数据缓存持久化到 `filesDir/music_cache/tracks.json`，避免每次启动全量扫描。
 * 扫描在 IO 线程执行，通过回调通知完成。
 */
open class MusicLibrary(private val context: Context) {

    /** 扫描完成回调（主线程） */
    var onScanComplete: ((trackCount: Int) -> Unit)? = null

    /** 扫描进度回调（主线程） */
    var onScanProgress: ((current: Int, total: Int) -> Unit)? = null

    /** 扫描状态变更回调（主线程） */
    var onScanStateChanged: ((scanning: Boolean) -> Unit)? = null

    private val gson = Gson()
    private val cacheDir: File
        get() = File(context.filesDir, "music_cache").apply { mkdirs() }
    private val cacheFile: File
        get() = File(cacheDir, "tracks.json")

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private val scanProgress = AtomicInteger(0)
    private val scanTotal = AtomicInteger(0)

    /** 当前音乐库中的所有曲目 */
    @Volatile
    private var tracks: List<MusicTrack> = emptyList()

    /** 是否正在扫描 */
    val isScanning: Boolean get() = scanning.get()

    init {
        loadCache()
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

                // 保存缓存
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
     */
    private fun scanLocalDirectory(path: String): List<MusicTrack> {
        val result = mutableListOf<MusicTrack>()
        val root = File(path)
        if (!root.exists() || !root.isDirectory) {
            Log.w(TAG, "Local path not exists or not a directory: $path")
            return result
        }

        val files = root.walkTopDown().filter { it.isFile && isAudioFile(it.name) }.toList()
        scanTotal.addAndGet(files.size)

        for ((index, file) in files.withIndex()) {
            try {
                val track = extractMetadata(file.absolutePath, MusicSource.LOCAL, file.lastModified())
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
     * 从本地文件提取元数据
     */
    private fun extractMetadata(path: String, source: MusicSource, lastModified: Long): MusicTrack? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: File(path).nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "未知歌手"
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)

            MusicTrack(
                id = path.hashCode().toString(),
                title = title,
                artist = artist,
                duration = duration,
                path = path,
                source = source,
                genre = genre,
                lastModified = lastModified,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata: $path", e)
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 从 SAF 文档提取元数据
     */
    private fun extractMetadataFromSaf(docFile: DocumentFile): MusicTrack? {
        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(docFile.uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: docFile.name?.substringBeforeLast('.') ?: "未知标题"
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: "未知歌手"
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = durationStr?.toLongOrNull() ?: 0L
                val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)

                MusicTrack(
                    id = docFile.uri.toString().hashCode().toString(),
                    title = title,
                    artist = artist,
                    duration = duration,
                    path = docFile.uri.toString(),
                    source = MusicSource.SAF,
                    genre = genre,
                    lastModified = docFile.lastModified(),
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
            it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower)
        }
    }

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
     * 加载缓存
     */
    private fun loadCache() {
        if (!cacheFile.exists()) {
            Log.i(TAG, "No cache file found")
            return
        }
        try {
            val json = cacheFile.readText()
            val type = object : TypeToken<List<MusicTrack>>() {}.type
            val cached: List<MusicTrack> = gson.fromJson(json, type) ?: emptyList()
            tracks = cached
            Log.i(TAG, "Loaded ${cached.size} tracks from cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache", e)
            cacheFile.delete()
        }
    }

    /**
     * 保存缓存
     */
    private fun saveCache() {
        try {
            val json = gson.toJson(tracks)
            cacheFile.writeText(json)
            Log.i(TAG, "Saved ${tracks.size} tracks to cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache", e)
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        tracks = emptyList()
        cacheFile.delete()
        Log.i(TAG, "Cache cleared")
    }

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

        /** 支持的音频文件扩展名 */
        private val AUDIO_EXTENSIONS = listOf(
            ".mp3", ".wav", ".aac", ".flac", ".ogg", ".m4a", ".wma", ".opus"
        )
    }
}
