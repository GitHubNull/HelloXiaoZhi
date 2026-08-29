package org.oxff.helloxiaozhi.ui.modal

import android.view.LayoutInflater
import android.widget.TextView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.ui.view.ModalHost

/**
 * 设备激活模态框（对应设计稿 activation-modal.js）。
 *
 * 展示 6 位验证码，「立即检查」触发一次激活轮询，「取消」关闭。
 */
class ActivationModal(
    private val modalHost: ModalHost,
    private val controller: XiaoZhiController,
) {

    private val context = modalHost.context
    private var codeView: TextView? = null
    private var checkBtn: TextView? = null

    fun show(code: String) {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.modal_activation, modalHost, false)
        codeView = root.findViewById(R.id.active_code)
        checkBtn = root.findViewById(R.id.btn_check_active)
        codeView?.text = code
        checkBtn?.setOnClickListener {
            checkBtn?.isEnabled = false
            checkBtn?.text = context.getString(R.string.activation_checking)
            controller.activationCheckNow()
        }
        root.findViewById<TextView>(R.id.btn_cancel_active).setOnClickListener {
            controller.cancelActivation()
            modalHost.dismiss()
        }
        modalHost.show(root)
    }

    /** 激活完成：更新按钮文案并自动关闭 */
    fun onActivated() {
        checkBtn?.isEnabled = true
        checkBtn?.text = context.getString(R.string.activation_activated)
        modalHost.dismiss()
    }

    /** 激活检查失败：恢复按钮 */
    fun onError() {
        checkBtn?.isEnabled = true
        checkBtn?.text = context.getString(R.string.activation_check_now)
    }
}
