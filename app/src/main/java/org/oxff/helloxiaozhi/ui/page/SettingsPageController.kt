package org.oxff.helloxiaozhi.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.ui.adapter.WakeTargetAdapter
import org.oxff.helloxiaozhi.ui.view.ToastHost
import org.oxff.helloxiaozhi.ui.view.XzSwitch

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
}
