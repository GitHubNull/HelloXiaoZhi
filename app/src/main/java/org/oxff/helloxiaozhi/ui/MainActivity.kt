package org.oxff.helloxiaozhi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.chat.ConnectionStatus
import org.oxff.helloxiaozhi.controller.XiaoZhiController

/**
 * 主页（对应 Web 端 App.vue）：顶部连接状态 + 聊天列表 +
 * 底部输入区 + 语音通话/设置入口。进入页面自动确保 WebSocket
 * 连接（官方模式先走 OTA 激活验证码流程）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var controller: XiaoZhiController
    private lateinit var adapter: MessageAdapter
    private lateinit var chatList: RecyclerView
    private lateinit var inputEdit: EditText
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private var activationDialog: ActivationDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controller = (application as XiaoZhiApp).controller

        adapter = MessageAdapter()
        chatList = findViewById(R.id.chat_list)
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = adapter
        inputEdit = findViewById(R.id.input_edit)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)

        findViewById<View>(R.id.btn_send).setOnClickListener { sendText() }
        findViewById<View>(R.id.btn_call).setOnClickListener { startVoiceCall() }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendText()
                true
            } else {
                false
            }
        }

        bindController()
    }

    override fun onResume() {
        super.onResume()
        controller.ensureConnected()
    }

    override fun onDestroy() {
        activationDialog?.dismiss()
        activationDialog = null
        unbindController()
        super.onDestroy()
    }

    // ---------------- Controller 回调绑定（Activity 销毁时解绑防止泄漏） ----------------

    private fun bindController() {
        controller.onConnectionStatusChanged = ::updateStatus
        controller.onChatMessage = { message ->
            adapter.add(message)
            chatList.scrollToPosition(adapter.itemCount - 1)
        }
        controller.onActivationCodeRequired = { code ->
            (activationDialog ?: ActivationDialog(this, controller).also { activationDialog = it })
                .show(code)
        }
        controller.onActivationCompleted = { activationDialog?.onActivated() }
        controller.onError = { message ->
            activationDialog?.onError(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        updateStatus(controller.connectionStatus)
    }

    private fun unbindController() {
        controller.onConnectionStatusChanged = null
        controller.onChatMessage = null
        controller.onActivationCodeRequired = null
        controller.onActivationCompleted = null
        controller.onError = null
    }

    // ---------------- 交互 ----------------

    private fun sendText() {
        val text = inputEdit.text.toString()
        if (text.isBlank()) return
        controller.sendTextMessage(text)
        inputEdit.setText("")
    }

    /** 申请录音权限后进入语音通话（targetSdk 27 需要运行时权限） */
    private fun startVoiceCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO,
            )
            return
        }
        enterVoiceCall()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enterVoiceCall()
        } else {
            Toast.makeText(this, R.string.permission_record_audio_required, Toast.LENGTH_LONG)
                .show()
        }
    }

    /** 启动录音后进入通话页（对应 App.vue showVoiceCallPanel） */
    private fun enterVoiceCall() {
        controller.startVoiceCall()
        startActivity(Intent(this, VoiceCallActivity::class.java))
    }

    /** 更新顶部连接状态指示（对应 ConnectionStatus.vue） */
    private fun updateStatus(status: ConnectionStatus) {
        val textRes: Int
        val colorRes: Int
        when (status) {
            ConnectionStatus.CONNECTED -> {
                textRes = R.string.status_connected
                colorRes = R.color.status_connected
            }
            ConnectionStatus.DISCONNECTED -> {
                textRes = R.string.status_disconnected
                colorRes = R.color.status_disconnected
            }
            ConnectionStatus.ERROR -> {
                textRes = R.string.status_error
                colorRes = R.color.status_error
            }
        }
        statusText.setText(textRes)
        statusText.setTextColor(ContextCompat.getColor(this, colorRes))
        (statusDot.background as? GradientDrawable)
            ?.setColor(ContextCompat.getColor(this, colorRes))
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 100
    }
}
