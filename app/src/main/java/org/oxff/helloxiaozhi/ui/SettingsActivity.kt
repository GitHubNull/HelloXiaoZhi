package org.oxff.helloxiaozhi.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.controller.XiaoZhiController

/**
 * 设置页（对应 Web 端 Setting/index.vue）：WS/OTA 地址、Token 开关
 * 与取值、设备 ID 展示。保存后立即生效（断开重连）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var controller: XiaoZhiController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        controller = (application as XiaoZhiApp).controller

        val wsUrlEdit = findViewById<EditText>(R.id.ws_url_edit)
        val otaUrlEdit = findViewById<EditText>(R.id.ota_url_edit)
        val tokenSwitch = findViewById<SwitchCompat>(R.id.token_switch)
        val tokenEdit = findViewById<EditText>(R.id.token_edit)
        val deviceIdValue = findViewById<TextView>(R.id.device_id_value)

        // 填充当前配置
        wsUrlEdit.setText(controller.config.wsUrl)
        otaUrlEdit.setText(controller.config.otaUrl)
        tokenSwitch.isChecked = controller.config.tokenEnable
        tokenEdit.setText(controller.config.token)
        tokenEdit.isEnabled = tokenSwitch.isChecked
        tokenSwitch.setOnCheckedChangeListener { _, checked -> tokenEdit.isEnabled = checked }
        deviceIdValue.text = controller.config.deviceId

        findViewById<View>(R.id.btn_save).setOnClickListener {
            val wsUrl = wsUrlEdit.text.toString().trim()
            val otaUrl = otaUrlEdit.text.toString().trim()
            val wsValid = wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://")
            val otaValid = otaUrl.startsWith("http://") || otaUrl.startsWith("https://")
            if (!wsValid || !otaValid) {
                Toast.makeText(this, R.string.settings_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            controller.config.wsUrl = wsUrl
            controller.config.otaUrl = otaUrl
            controller.config.tokenEnable = tokenSwitch.isChecked
            controller.config.token = tokenEdit.text.toString().trim()
            // 断开当前连接，下次 ensureConnected 时以新配置重连
            controller.applySettings()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
