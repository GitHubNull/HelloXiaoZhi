package org.oxff.helloxiaozhi.ui.modal

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.data.Bot
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.ui.view.AvatarPalette
import org.oxff.helloxiaozhi.ui.view.ModalHost
import org.oxff.helloxiaozhi.ui.view.ToastHost
import org.oxff.helloxiaozhi.util.MacGenerator

/**
 * 添加机器人模态框（对应设计稿 add-bot-modal.js）。
 *
 * 头像颜色选择、MAC 地址管理（自动生成 / 重新生成 / 自定义标记）、
 * 「获取该 MAC 的激活码」走真实 OTA 注册、表单校验与提交。
 */
class AddBotModal(
    private val modalHost: ModalHost,
    private val repository: BotRepository,
    private val controller: XiaoZhiController,
    private val toast: ToastHost,
    private val onAdded: () -> Unit,
) {

    private val context = modalHost.context
    private var selectedColorIndex = 0
    private var macCustomized = false
    private var macCodeLoading = false

    private lateinit var root: View
    private lateinit var avatarPreview: View
    private lateinit var avatarPreviewText: TextView
    private lateinit var avatarPopup: LinearLayout
    private lateinit var avatarGrid: GridLayout
    private lateinit var macInput: EditText
    private lateinit var macSourceTag: TextView
    private lateinit var macActivatePanel: LinearLayout
    private lateinit var macActivateCode: TextView
    private lateinit var btnGetMacCode: TextView
    private lateinit var nameInput: EditText
    private lateinit var tagsInput: EditText
    private lateinit var descInput: EditText

    fun show() {
        root = LayoutInflater.from(context).inflate(R.layout.modal_add_bot, modalHost, false)
        bindViews()
        resetForm()
        modalHost.show(root)
    }

    private fun bindViews() {
        avatarPreview = root.findViewById(R.id.modal_avatar_preview)
        avatarPreviewText = root.findViewById(R.id.avatar_preview_text)
        avatarPopup = root.findViewById(R.id.avatar_popup)
        avatarGrid = root.findViewById(R.id.avatar_popup_grid)
        macInput = root.findViewById(R.id.new_bot_mac)
        macSourceTag = root.findViewById(R.id.mac_source_tag)
        macActivatePanel = root.findViewById(R.id.mac_activate_panel)
        macActivateCode = root.findViewById(R.id.mac_activate_code)
        btnGetMacCode = root.findViewById(R.id.btn_get_mac_code)
        nameInput = root.findViewById(R.id.new_bot_name)
        tagsInput = root.findViewById(R.id.new_bot_tags)
        descInput = root.findViewById(R.id.new_bot_desc)

        avatarPreview.setOnClickListener { toggleAvatarPopup() }
        root.findViewById<ImageButton>(R.id.btn_regen_mac).setOnClickListener { regenMac() }
        btnGetMacCode.setOnClickListener { requestMacCode() }
        macInput.setOnFocusChangeListener { _, _ -> markMacCustomized() }
        macInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                markMacCustomized()
                collapseMacCodePanel()
            }
        })
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateAvatarPreview(s?.toString()?.trim().orEmpty())
            }
        })

        root.findViewById<TextView>(R.id.btn_confirm_add).setOnClickListener { confirm() }
        root.findViewById<TextView>(R.id.btn_cancel_add).setOnClickListener { modalHost.dismiss() }
    }

    private fun resetForm() {
        selectedColorIndex = 0
        macCustomized = false
        macCodeLoading = false
        nameInput.setText("")
        tagsInput.setText("")
        descInput.setText("")
        macInput.setText(controller.config.deviceId)
        updateMacTag()
        updateAvatarPreview("")
        avatarPopup.visibility = View.GONE
        collapseMacCodePanel()
        resetMacCodeBtn()
    }

    // ---------------- 头像颜色选择 ----------------

    private fun toggleAvatarPopup() {
        if (avatarPopup.visibility == View.VISIBLE) {
            avatarPopup.visibility = View.GONE
        } else {
            renderAvatarGrid()
            avatarPopup.visibility = View.VISIBLE
        }
    }

    private fun renderAvatarGrid() {
        avatarGrid.removeAllViews()
        val size = (52 * context.resources.displayMetrics.density).toInt()
        val margin = (5 * context.resources.displayMetrics.density).toInt()
        for (i in 0 until AvatarPalette.SIZE) {
            val option = TextView(context).apply {
                text = avatarPreviewText.text
                setTextColor(ContextCompat.getColor(context, R.color.xz_text_on_primary))
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                background = AvatarPalette.avatarBackground(
                    context, i,
                    context.resources.getDimension(R.dimen.xz_radius_avatar),
                )
                if (i == selectedColorIndex) {
                    // 选中态：主色描边
                    background = AvatarPalette.avatarBackground(
                        context, i,
                        context.resources.getDimension(R.dimen.xz_radius_avatar),
                    ).apply {
                        setStroke(
                            (2.5f * context.resources.displayMetrics.density).toInt(),
                            ContextCompat.getColor(context, R.color.xz_primary),
                        )
                    }
                }
                setOnClickListener {
                    selectedColorIndex = i
                    updateAvatarPreview(nameInput.text.toString().trim())
                    renderAvatarGrid()
                    avatarPopup.postDelayed({ avatarPopup.visibility = View.GONE }, 300)
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(margin, margin, margin, margin)
            }
            avatarGrid.addView(option, lp)
        }
    }

    private fun updateAvatarPreview(name: String) {
        val display = if (name.isNotEmpty()) name.take(1) else context.getString(R.string.add_bot_avatar_placeholder)
        avatarPreviewText.text = display
        avatarPreview.background = AvatarPalette.avatarBackground(
            context, selectedColorIndex,
            context.resources.getDimension(R.dimen.xz_radius_lg),
        )
    }

    // ---------------- MAC 地址 ----------------

    private fun regenMac() {
        macInput.setText(MacGenerator.random())
        macCustomized = false
        updateMacTag()
        collapseMacCodePanel()
        resetMacCodeBtn()
    }

    private fun markMacCustomized() {
        if (macInput.hasFocus()) {
            macCustomized = true
            updateMacTag()
        }
    }

    private fun updateMacTag() {
        if (macCustomized) {
            macSourceTag.text = context.getString(R.string.add_bot_mac_source_custom)
            macSourceTag.setBackgroundResource(R.drawable.bg_pill_warning)
            macSourceTag.setTextColor(ContextCompat.getColor(context, R.color.xz_warning))
        } else {
            macSourceTag.text = context.getString(R.string.add_bot_mac_source_auto)
            macSourceTag.setBackgroundResource(R.drawable.bg_pill_success)
            macSourceTag.setTextColor(ContextCompat.getColor(context, R.color.xz_success))
        }
    }

    // ---------------- 获取该 MAC 的激活码 ----------------

    private fun requestMacCode() {
        if (macCodeLoading) return
        val mac = macInput.text.toString().trim()
        if (!MacGenerator.isValid(mac)) {
            toast.show(context.getString(R.string.toast_mac_invalid), ToastHost.Kind.ERROR)
            return
        }
        if (macActivatePanel.visibility == View.VISIBLE) {
            collapseMacCodePanel()
            return
        }
        macCodeLoading = true
        btnGetMacCode.isEnabled = false
        btnGetMacCode.text = context.getString(R.string.add_bot_get_code_loading)
        macActivateCode.text = context.getString(R.string.add_bot_code_placeholder)
        macActivatePanel.visibility = View.VISIBLE

        controller.requestActivationCodeFor(MacGenerator.normalize(mac)) { code, error ->
            macCodeLoading = false
            btnGetMacCode.isEnabled = true
            btnGetMacCode.text = context.getString(R.string.add_bot_get_code_again)
            when {
                error != null -> toast.show(error, ToastHost.Kind.ERROR)
                code != null -> macActivateCode.text = code
                else -> {
                    macActivateCode.text = "—"
                    toast.show(context.getString(R.string.add_bot_code_already_bound), ToastHost.Kind.SUCCESS)
                }
            }
        }
    }

    private fun collapseMacCodePanel() {
        macActivatePanel.visibility = View.GONE
    }

    private fun resetMacCodeBtn() {
        macCodeLoading = false
        btnGetMacCode.isEnabled = true
        btnGetMacCode.text = context.getString(R.string.add_bot_get_code)
    }

    // ---------------- 提交 ----------------

    private fun confirm() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            toast.show(context.getString(R.string.toast_bot_name_required), ToastHost.Kind.ERROR)
            return
        }
        val mac = macInput.text.toString().trim()
        if (!MacGenerator.isValid(mac)) {
            toast.show(context.getString(R.string.toast_mac_invalid), ToastHost.Kind.ERROR)
            return
        }
        val normalizedMac = MacGenerator.normalize(mac)
        repository.botByMac(normalizedMac)?.let {
            toast.show(
                context.getString(R.string.toast_mac_duplicated, it.name),
                ToastHost.Kind.ERROR,
            )
            return
        }

        val tags = tagsInput.text.toString().trim()
            .split(Regex("[,，]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(context.getString(R.string.add_bot_default_tag)) }
        val desc = descInput.text.toString().trim()
            .ifEmpty { context.getString(R.string.add_bot_default_desc) }

        val bot = Bot(
            id = "bot_" + System.currentTimeMillis().toString(36) +
                (1000..9999).random().toString(36),
            name = name,
            avatarText = name.take(1),
            avatarColorIndex = selectedColorIndex,
            tags = tags,
            desc = desc,
            mac = normalizedMac,
            activated = false,
            createdAt = System.currentTimeMillis(),
        )

        repository.addBot(bot)
        modalHost.dismiss()
        toast.show(context.getString(R.string.toast_bot_added, name), ToastHost.Kind.SUCCESS)
        onAdded()
    }
}
