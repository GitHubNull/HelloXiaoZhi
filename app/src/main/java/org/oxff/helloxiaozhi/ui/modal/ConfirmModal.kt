package org.oxff.helloxiaozhi.ui.modal

import android.view.LayoutInflater
import android.widget.TextView
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.ui.view.ModalHost

/**
 * 通用确认模态框（对应设计稿 #reset-modal），重置与删除机器人共用。
 */
class ConfirmModal(
    private val modalHost: ModalHost,
    private val title: String,
    private val desc: String,
    private val confirmText: String,
    private val onConfirm: () -> Unit,
) {

    fun show() {
        val context = modalHost.context
        val root = LayoutInflater.from(context)
            .inflate(R.layout.modal_confirm, modalHost, false)
        root.findViewById<TextView>(R.id.confirm_title).text = title
        root.findViewById<TextView>(R.id.confirm_desc).text = desc
        root.findViewById<TextView>(R.id.btn_confirm_reset).apply {
            text = confirmText
            setOnClickListener {
                modalHost.dismiss()
                onConfirm()
            }
        }
        root.findViewById<TextView>(R.id.btn_cancel_reset).setOnClickListener {
            modalHost.dismiss()
        }
        modalHost.show(root)
    }
}
