package org.oxff.helloxiaozhi.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.assistant.AssistantStatusDetector
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.ui.adapter.WakeTargetAdapter
import org.oxff.helloxiaozhi.ui.view.ToastHost
import org.oxff.helloxiaozhi.ui.view.XzSwitch
import org.oxff.helloxiaozhi.wake.WakeWordService

/**
 * 设置 Tab 页面控制器（对应设计稿 settings.js）。
 *
 * 配置加载/保存、Token 开关联动、复制设备 ID、唤醒目标选择、重置数据。
 */
class SettingsPageController(
    private val root: View,
    private val repository: BotRepository,
    private val controller: XiaoZhiController,
    private val toast: ToastHost,
    private val onReset: () -> Unit,
    private val onGetCode: () -> Unit,
    private val localDirLauncher: ActivityResultLauncher<Intent>,
    private val safDirLauncher: ActivityResultLauncher<Intent>,
) {

    private val wsUrlEdit = root.findViewById<EditText>(R.id.ws_url_edit)
    private val otaUrlEdit = root.findViewById<EditText>(R.id.ota_url_edit)
    private val tokenSwitch = root.findViewById<XzSwitch>(R.id.token_switch)
    private val tokenWrap = root.findViewById<View>(R.id.token_wrap)
    private val tokenEdit = root.findViewById<EditText>(R.id.token_edit)
    private val deviceIdEdit = root.findViewById<EditText>(R.id.device_id_edit)
    private val btnCopy = root.findViewById<View>(R.id.btn_copy)
    private val btnGetCode = root.findViewById<TextView>(R.id.btn_get_code)
    private val btnReset = root.findViewById<TextView>(R.id.btn_reset)
    private val btnSave = root.findViewById<TextView>(R.id.btn_save)
    private val wakeTargetList = root.findViewById<RecyclerView>(R.id.wake_target_list)
    private val wakeWordSwitch = root.findViewById<XzSwitch>(R.id.wake_word_switch)
    private val wakeSensitivitySlider = root.findViewById<SeekBar>(R.id.wake_sensitivity_slider)
    private val wakeSensitivityValue = root.findViewById<TextView>(R.id.wake_sensitivity_value)
    private val wakeSoundSwitch = root.findViewById<XzSwitch>(R.id.wake_sound_switch)
    private val aiDoneSoundSwitch = root.findViewById<XzSwitch>(R.id.ai_done_sound_switch)
    private val wakeStatusText = root.findViewById<TextView>(R.id.wake_status_text)
    private val assistantStatusText = root.findViewById<TextView>(R.id.assistant_status_text)
    private val btnAssistantSettings = root.findViewById<TextView>(R.id.btn_assistant_settings)
    private val btnAssistantCheck = root.findViewById<TextView>(R.id.btn_assistant_check)
    private val robotActionSwitch = root.findViewById<XzSwitch>(R.id.robot_action_switch)
    private val robotStatusText = root.findViewById<TextView>(R.id.robot_status_text)

    // 音乐播放设置
    private val musicSwitch = root.findViewById<XzSwitch>(R.id.music_switch)
    private val musicLocalPathText = root.findViewById<TextView>(R.id.music_local_path_text)
    private val btnMusicLocalSelect = root.findViewById<TextView>(R.id.btn_music_local_select)
    private val musicSafUriText = root.findViewById<TextView>(R.id.music_saf_uri_text)
    private val btnMusicSafSelect = root.findViewById<TextView>(R.id.btn_music_saf_select)
    private val btnMusicScan = root.findViewById<TextView>(R.id.btn_music_scan)
    private val musicScanStatus = root.findViewById<TextView>(R.id.music_scan_status)
    private val musicTracksCount = root.findViewById<TextView>(R.id.music_tracks_count)

    private val wakeAdapter = WakeTargetAdapter(onSelect = { bot ->
        repository.wakeTargetBotId = bot.id
        toast.show(
            root.context.getString(R.string.toast_wake_target_set, bot.name),
            ToastHost.Kind.SUCCESS,
        )
        renderWakeTargets()
    })

    init {
        wakeTargetList.layoutManager = LinearLayoutManager(root.context)
        wakeTargetList.adapter = wakeAdapter

        tokenSwitch.onCheckedChange = { checked ->
            tokenWrap.visibility = if (checked) View.VISIBLE else View.GONE
        }
        btnCopy.setOnClickListener { copyDeviceId() }
        btnGetCode.setOnClickListener { onGetCode() }
        btnReset.setOnClickListener { onReset() }
        btnSave.setOnClickListener { save() }

        // 唤醒词检测开关
        wakeWordSwitch.onCheckedChange = label@{ checked ->
            if (checked) {
                if (!checkAudioPermission()) {
                    toast.show(root.context.getString(R.string.permission_record_audio_required), ToastHost.Kind.ERROR)
                    wakeWordSwitch.setChecked(false, animate = true)
                    return@label
                }
                controller.config.wakeWordEnabled = true
                WakeWordService.start(root.context)
                toast.show(root.context.getString(R.string.toast_wake_word_enabled), ToastHost.Kind.SUCCESS)
            } else {
                controller.config.wakeWordEnabled = false
                WakeWordService.stop(root.context)
                toast.show(root.context.getString(R.string.toast_wake_word_disabled), ToastHost.Kind.SUCCESS)
            }
            updateWakeStatus()
        }

        // 唤醒灵敏度滑块
        wakeSensitivitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                wakeSensitivityValue.text = "$progress%"
                if (fromUser) {
                    controller.config.wakeWordSensitivity = progress / 100f
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 唤醒提示音开关
        wakeSoundSwitch.onCheckedChange = { checked ->
            controller.config.wakeSoundEnabled = checked
        }

        // AI 结束提示音开关
        aiDoneSoundSwitch.onCheckedChange = { checked ->
            controller.config.aiDoneSoundEnabled = checked
        }

        // 机器人动作开关（同时控制 MCP 主路径与关键词降级兜底）
        robotActionSwitch.onCheckedChange = { checked ->
            controller.config.robotActionEnabled = checked
            controller.mcpActionHandler.enabled = checked
            controller.actionMapper.enabled = checked
        }

        // 音乐功能开关
        musicSwitch.onCheckedChange = { checked ->
            controller.config.musicEnabled = checked
            controller.musicActionMapper.enabled = checked
            toast.show(
                root.context.getString(if (checked) R.string.toast_music_enabled else R.string.toast_music_disabled),
                ToastHost.Kind.SUCCESS,
            )
        }

        // 本地音乐目录选择
        btnMusicLocalSelect.setOnClickListener {
            localDirLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }

        // SAF 目录选择
        btnMusicSafSelect.setOnClickListener {
            safDirLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }

        // 扫描音乐库
        btnMusicScan.setOnClickListener {
            val localPath = controller.config.musicLocalPath
            val safUri = controller.config.musicSafUri
            if (localPath.isEmpty() && safUri.isEmpty()) {
                toast.show(root.context.getString(R.string.settings_music_not_configured), ToastHost.Kind.ERROR)
                return@setOnClickListener
            }
            toast.show(root.context.getString(R.string.toast_music_scan_started), ToastHost.Kind.SUCCESS)
            controller.musicLibrary.scan(localPath, safUri)
        }

        // 音乐库扫描回调
        controller.musicLibrary.onScanStateChanged = { scanning ->
            musicScanStatus.text = if (scanning) {
                root.context.getString(R.string.settings_music_scanning)
            } else {
                root.context.getString(R.string.settings_music_not_configured)
            }
        }
        controller.musicLibrary.onScanComplete = { count ->
            musicScanStatus.text = root.context.getString(R.string.settings_music_scan_complete, count)
            updateMusicTracksCount()
        }

        // 系统语音助手
        btnAssistantSettings.setOnClickListener {
            try {
                root.context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (_: Exception) {
                root.context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }
        btnAssistantCheck.setOnClickListener {
            updateAssistantStatus()
        }
    }

    /** 刷新表单（Tab 切换到设置 / 数据变更时调用） */
    fun render() {
        wsUrlEdit.setText(controller.config.wsUrl)
        otaUrlEdit.setText(controller.config.otaUrl)
        tokenSwitch.setChecked(controller.config.tokenEnable, animate = false)
        tokenWrap.visibility = if (controller.config.tokenEnable) View.VISIBLE else View.GONE
        tokenEdit.setText(controller.config.token)
        deviceIdEdit.setText(controller.config.deviceId)
        renderWakeTargets()

        // 唤醒词设置
        wakeWordSwitch.setChecked(controller.config.wakeWordEnabled, animate = false)
        val sensitivityPercent = (controller.config.wakeWordSensitivity * 100).toInt()
        wakeSensitivitySlider.progress = sensitivityPercent
        wakeSensitivityValue.text = "$sensitivityPercent%"
        wakeSoundSwitch.setChecked(controller.config.wakeSoundEnabled, animate = false)
        aiDoneSoundSwitch.setChecked(controller.config.aiDoneSoundEnabled, animate = false)
        robotActionSwitch.setChecked(controller.config.robotActionEnabled, animate = false)
        musicSwitch.setChecked(controller.config.musicEnabled, animate = false)
        updateWakeStatus()
        updateAssistantStatus()
        updateRobotStatus()
        updateMusicSettings()
    }

    private fun renderWakeTargets() {
        wakeAdapter.submit(repository.bots(), repository.wakeTargetBotId)
    }

    private fun copyDeviceId() {
        val text = deviceIdEdit.text.toString()
        val clipboard = root.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("device_id", text))
        toast.show(root.context.getString(R.string.toast_device_id_copied), ToastHost.Kind.SUCCESS)
    }

    private fun save() {
        val context = root.context
        val wsUrl = wsUrlEdit.text.toString().trim()
        val otaUrl = otaUrlEdit.text.toString().trim()
        val tokenEnabled = tokenSwitch.isChecked
        val token = tokenEdit.text.toString().trim()

        if (wsUrl.isEmpty()) {
            toast.show(context.getString(R.string.toast_ws_url_required), ToastHost.Kind.ERROR)
            return
        }
        if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
            toast.show(context.getString(R.string.toast_ws_url_invalid), ToastHost.Kind.ERROR)
            return
        }
        if (otaUrl.isEmpty()) {
            toast.show(context.getString(R.string.toast_ota_url_required), ToastHost.Kind.ERROR)
            return
        }
        if (!otaUrl.startsWith("http://") && !otaUrl.startsWith("https://")) {
            toast.show(context.getString(R.string.toast_ota_url_invalid), ToastHost.Kind.ERROR)
            return
        }
        if (tokenEnabled && token.isEmpty()) {
            toast.show(context.getString(R.string.toast_token_required), ToastHost.Kind.ERROR)
            return
        }

        controller.config.wsUrl = wsUrl
        controller.config.otaUrl = otaUrl
        controller.config.tokenEnable = tokenEnabled
        controller.config.token = token
        // 断开当前连接，下次 ensureConnected 时以新配置重连
        controller.applySettings()
        toast.show(context.getString(R.string.toast_settings_saved), ToastHost.Kind.SUCCESS)
    }

    // ---------------- 唤醒词与助手状态 ----------------

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            root.context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateWakeStatus() {
        val context = root.context
        wakeStatusText.text = when {
            !checkAudioPermission() -> context.getString(R.string.settings_wake_status_no_permission)
            controller.config.wakeWordEnabled -> context.getString(R.string.settings_wake_status_running)
            else -> context.getString(R.string.settings_wake_status_stopped)
        }
    }

    private fun updateRobotStatus() {
        val context = root.context
        robotStatusText.text = if (controller.robotController.isAvailable) {
            context.getString(R.string.settings_robot_status_available)
        } else {
            context.getString(R.string.settings_robot_status_unavailable)
        }
    }

    private fun updateMusicSettings() {
        val context = root.context
        val localPath = controller.config.musicLocalPath
        val safUri = controller.config.musicSafUri

        musicLocalPathText.text = if (localPath.isNotEmpty()) localPath else context.getString(R.string.settings_music_not_configured)
        musicSafUriText.text = if (safUri.isNotEmpty()) safUri else context.getString(R.string.settings_music_not_configured)

        updateMusicTracksCount()
    }

    private fun updateMusicTracksCount() {
        val count = controller.musicLibrary.trackCount()
        musicTracksCount.text = root.context.getString(R.string.settings_music_tracks_count, count)
    }

    private fun updateAssistantStatus() {
        val context = root.context
        // 多数据源检测（role + Secure 键），修复「已设为默认语音助手却显示未设置」：
        // Android 10+ 默认助手以 ROLE_ASSISTANT 角色为准，Secure 键仅是框架回写的兼容层
        val isDefault = try {
            AssistantStatusDetector.isDefaultAssistant(context)
        } catch (_: Exception) {
            false
        }
        assistantStatusText.text = if (isDefault) {
            context.getString(R.string.settings_assistant_status_set)
        } else {
            context.getString(R.string.settings_assistant_status_not_set)
        }
    }
}
