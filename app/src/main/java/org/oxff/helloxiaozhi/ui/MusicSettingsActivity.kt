package org.oxff.helloxiaozhi.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.data.db.ArtistType
import org.oxff.helloxiaozhi.ui.adapter.ArtistDictAdapter
import org.oxff.helloxiaozhi.ui.view.ToastHost
import org.oxff.helloxiaozhi.ui.view.XzSwitch
import java.util.concurrent.Executors

/**
 * 音乐配置页：音乐开关 / 目录选择 / 扫描 / 歌手词典管理。
 *
 * 自管理文件选择/创建 launcher，不依赖 MainActivity。
 * 词典数据库操作在 IO 线程执行，结果回主线程刷新 UI。
 */
class MusicSettingsActivity : AppCompatActivity() {

    private lateinit var controller: XiaoZhiController
    private lateinit var toastHost: ToastHost

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // 音乐配置视图
    private lateinit var musicSwitch: XzSwitch
    private lateinit var musicLocalPathText: TextView
    private lateinit var musicSafUriText: TextView
    private lateinit var musicScanStatus: TextView
    private lateinit var musicTracksCount: TextView

    // 词典管理视图
    private lateinit var dictBuiltinCount: TextView
    private lateinit var dictUserEmpty: TextView
    private lateinit var dictList: RecyclerView
    private lateinit var dictAdapter: ArtistDictAdapter

    // 待导入的词典类型（由用户点击"导入歌手/乐队"决定）
    private var pendingImportType: ArtistType = ArtistType.ARTIST

    // 本地目录选择
    private val localDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                controller.config.musicLocalPath = it.toString()
                renderMusicPaths()
            }
        }

    // SAF 目录选择
    private val safDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                controller.config.musicSafUri = it.toString()
                renderMusicPaths()
            }
        }

    // 词典导入文件选择
    private val dictImportLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importDictFromUri(it) }
        }

    // 词典导出文件创建
    private val dictExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { exportDictToUri(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_settings)
        controller = (application as XiaoZhiApp).controller
        toastHost = findViewById(R.id.toast_host)

        bindViews()
        bindListeners()
        bindScanCallbacks()
        renderAll()
        refreshDict()
    }

    override fun onDestroy() {
        // 解绑扫描回调，避免持有 Activity 泄漏
        controller.musicLibrary.onScanStateChanged = null
        controller.musicLibrary.onScanComplete = null
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        musicSwitch = findViewById(R.id.music_switch)
        musicLocalPathText = findViewById(R.id.music_local_path_text)
        musicSafUriText = findViewById(R.id.music_saf_uri_text)
        musicScanStatus = findViewById(R.id.music_scan_status)
        musicTracksCount = findViewById(R.id.music_tracks_count)

        dictBuiltinCount = findViewById(R.id.music_dict_builtin_count)
        dictUserEmpty = findViewById(R.id.music_dict_user_empty)
        dictList = findViewById(R.id.music_dict_list)

        dictAdapter = ArtistDictAdapter(onDelete = { entry -> deleteDictEntry(entry.id) })
        dictList.layoutManager = LinearLayoutManager(this)
        dictList.adapter = dictAdapter
    }

    private fun bindListeners() {
        findViewById<View>(R.id.btn_music_settings_back).setOnClickListener { finish() }

        musicSwitch.onCheckedChange = { checked ->
            controller.config.musicEnabled = checked
            // 同步本地关键词兜底映射器开关（延迟兜底机制由该开关控制）
            controller.musicActionMapper.enabled = checked
            toastHost.show(
                getString(if (checked) R.string.toast_music_enabled else R.string.toast_music_disabled),
                ToastHost.Kind.SUCCESS,
            )
        }

        findViewById<View>(R.id.btn_music_local_select).setOnClickListener {
            localDirLauncher.launch(null)
        }
        findViewById<View>(R.id.btn_music_saf_select).setOnClickListener {
            safDirLauncher.launch(null)
        }
        findViewById<View>(R.id.btn_music_scan).setOnClickListener {
            startScan()
        }

        findViewById<View>(R.id.btn_dict_import_artist).setOnClickListener {
            pendingImportType = ArtistType.ARTIST
            dictImportLauncher.launch("text/plain")
        }
        findViewById<View>(R.id.btn_dict_import_band).setOnClickListener {
            pendingImportType = ArtistType.BAND
            dictImportLauncher.launch("text/plain")
        }
        findViewById<View>(R.id.btn_dict_export).setOnClickListener {
            dictExportLauncher.launch("artist_dict.txt")
        }
    }

    private fun bindScanCallbacks() {
        controller.musicLibrary.onScanStateChanged = { scanning ->
            musicScanStatus.text = getString(
                if (scanning) R.string.settings_music_scanning else R.string.settings_music_not_configured,
            )
        }
        controller.musicLibrary.onScanComplete = { count ->
            musicScanStatus.text = getString(R.string.settings_music_scan_complete, count)
            updateTracksCount()
        }
    }

    private fun renderAll() {
        musicSwitch.setChecked(controller.config.musicEnabled, animate = false)
        renderMusicPaths()
        updateTracksCount()
    }

    private fun renderMusicPaths() {
        val localPath = controller.config.musicLocalPath
        val safUri = controller.config.musicSafUri
        musicLocalPathText.text =
            if (localPath.isNotEmpty()) localPath else getString(R.string.settings_music_not_configured)
        musicSafUriText.text =
            if (safUri.isNotEmpty()) safUri else getString(R.string.settings_music_not_configured)
    }

    private fun updateTracksCount() {
        musicTracksCount.text = getString(R.string.settings_music_tracks_count, controller.musicLibrary.trackCount())
    }

    private fun startScan() {
        val localPath = controller.config.musicLocalPath
        val safUri = controller.config.musicSafUri
        if (localPath.isEmpty() && safUri.isEmpty()) {
            toastHost.show(getString(R.string.settings_music_not_configured), ToastHost.Kind.ERROR)
            return
        }
        toastHost.show(getString(R.string.toast_music_scan_started), ToastHost.Kind.SUCCESS)
        controller.musicLibrary.scan(localPath, safUri)
    }

    // ---------------- 词典管理 ----------------

    private fun refreshDict() {
        ioExecutor.execute {
            val dict = controller.musicLibrary.dictionary
            val builtin = dict.builtinCount()
            val userEntries = dict.allUserImported()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                dictBuiltinCount.text = getString(R.string.music_dict_builtin_count_value, builtin)
                dictUserEmpty.visibility = if (userEntries.isEmpty()) View.VISIBLE else View.GONE
                dictAdapter.submit(userEntries)
            }
        }
    }

    private fun importDictFromUri(uri: Uri) {
        ioExecutor.execute {
            try {
                val text = contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: ""
                val result = controller.musicLibrary.dictionary.importFromText(text, pendingImportType)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    toastHost.show(
                        getString(R.string.music_dict_import_result, result.imported, result.skipped),
                        ToastHost.Kind.SUCCESS,
                    )
                    refreshDict()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    toastHost.show(getString(R.string.music_dict_import_failed), ToastHost.Kind.ERROR)
                }
            }
        }
    }

    private fun exportDictToUri(uri: Uri) {
        ioExecutor.execute {
            try {
                val text = controller.musicLibrary.dictionary.exportUserImported()
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                }
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    toastHost.show(getString(R.string.music_dict_export_done), ToastHost.Kind.SUCCESS)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    toastHost.show(getString(R.string.music_dict_export_failed), ToastHost.Kind.ERROR)
                }
            }
        }
    }

    private fun deleteDictEntry(id: Long) {
        ioExecutor.execute {
            controller.musicLibrary.dictionary.deleteUserImported(id)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                toastHost.show(getString(R.string.music_dict_deleted), ToastHost.Kind.SUCCESS)
                refreshDict()
            }
        }
    }
}
