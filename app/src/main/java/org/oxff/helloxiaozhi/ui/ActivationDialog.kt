package org.oxff.helloxiaozhi.ui

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.controller.XiaoZhiController

/**
 * 设备激活（验证码）对话框，对应官方固件激活阶段的 UI 交互：
 * 展示 6 位验证码，用户到 xiaozhi.me 控制台添加设备后，可手动
 * 触发立即检查；后台每 5 秒自动轮询，激活完成后自动关闭。
 */
class ActivationDialog(
    private val activity: AppCompatActivity,
    private val controller: XiaoZhiController,
) {

    private var dialog: AlertDialog? = null
    private var codeText: TextView? = null
    private var statusText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 展示验证码；对话框已展示时仅刷新验证码文本 */
    fun show(code: String) {
        val current = dialog
        if (current?.isShowing == true) {
            codeText?.text = code
            return
        }
        val view = activity.layoutInflater.inflate(R.layout.dialog_activation, null)
        codeText = view.findViewById(R.id.activation_code_text)
        statusText = view.findViewById(R.id.activation_status_text)
        codeText?.text = code
        statusText?.setText(R.string.activation_waiting)
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.activation_title)
            .setView(view)
            .setNegativeButton(R.string.activation_cancel) { _, _ -> cancel() }
            .setPositiveButton(R.string.activation_done) { _, _ -> checkNow() }
            .setCancelable(false)
            .create()
        dialog?.show()
    }

    /** 用户点击"已添加设备，立即检查" */
    private fun checkNow() {
        statusText?.setText(R.string.activation_waiting)
        controller.activationCheckNow()
    }

    /** 用户取消激活 */
    private fun cancel() {
        controller.cancelActivation()
        dismiss()
    }

    /** 激活完成：更新文案后自动关闭 */
    fun onActivated() {
        statusText?.setText(R.string.activation_activated)
        handler.postDelayed({ dismiss() }, AUTO_DISMISS_MS)
    }

    /** 激活检查失败：更新状态文案 */
    fun onError(message: String) {
        if (dialog?.isShowing != true) return
        statusText?.text = activity.getString(R.string.activation_error, message)
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
    }

    private companion object {
        const val AUTO_DISMISS_MS = 800L
    }
}
