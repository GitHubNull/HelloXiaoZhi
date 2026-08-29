package org.oxff.helloxiaozhi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.chat.ConnectionStatus
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.data.BotRepository
import org.oxff.helloxiaozhi.ui.modal.ActivationModal
import org.oxff.helloxiaozhi.ui.modal.AddBotModal
import org.oxff.helloxiaozhi.ui.modal.ConfirmModal
import org.oxff.helloxiaozhi.ui.page.ChatDetailController
import org.oxff.helloxiaozhi.ui.page.ChatPageController
import org.oxff.helloxiaozhi.ui.page.ContactsPageController
import org.oxff.helloxiaozhi.ui.page.SettingsPageController
import org.oxff.helloxiaozhi.ui.view.ModalHost
import org.oxff.helloxiaozhi.ui.view.SlideInContainer
import org.oxff.helloxiaozhi.ui.view.ToastHost

/**
 * 三 Tab 外壳（对应设计稿 index.html）：
 * 顶部导航栏 + 聊天/通讯录/设置三个 Tab + 对话详情滑入层 + 模态框/Toast 宿主。
 *
 * 所有 controller 回调集中在此绑定，再分发给各页面控制器——
 * 避免多个页面争抢单槽回调导致后绑定者胜出、先绑定者静默失效。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var controller: XiaoZhiController
    private lateinit var repository: BotRepository

    private lateinit var toastHost: ToastHost
    private lateinit var modalHost: ModalHost
    private lateinit var chatDetailContainer: SlideInContainer

    private lateinit var chatPage: ChatPageController
    private lateinit var contactsPage: ContactsPageController
    private lateinit var settingsPage: SettingsPageController
    private lateinit var chatDetail: ChatDetailController

    private lateinit var activationModal: ActivationModal
    private lateinit var addBotModal: AddBotModal

    private var currentTab = Tab.CHAT

    private enum class Tab { CHAT, CONTACTS, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = application as XiaoZhiApp
        controller = app.controller
        repository = app.repository

        bindViews()
        bindPages()
        bindModals()
        bindController()
        bindTabs()
        bindRepository()

        // 首次启动：默认打开唤醒目标机器人的对话
        repository.defaultBot()?.let { chatDetail.open(it.id) }
    }

    override fun onResume() {
        super.onResume()
        controller.ensureConnected()
        renderAll()
    }

    override fun onPause() {
        super.onPause()
        repository.flush()
    }

    override fun onDestroy() {
        // 先解绑再销毁：repository 是应用级单例，持有本页回调会泄漏 Activity
        repository.onDataChanged = null
        unbindController()
        super.onDestroy()
    }

    // ---------------- 视图绑定 ----------------

    private fun bindViews() {
        toastHost = findViewById(R.id.toast_host)
        modalHost = findViewById(R.id.modal_host)
        chatDetailContainer = findViewById(R.id.chat_detail_container)
    }

    private fun bindPages() {
        chatPage = ChatPageController(
            root = findViewById(R.id.page_chat),
            repository = repository,
            onOpenChat = { chatDetail.open(it) },
            onGoContacts = { switchTab(Tab.CONTACTS) },
        )
        contactsPage = ContactsPageController(
            root = findViewById(R.id.page_contacts),
            repository = repository,
            onOpenChat = { botId ->
                controller.switchActiveBot(botId)
                switchTab(Tab.CHAT)
                chatDetail.open(botId)
            },
            onDeleteBot = { bot -> confirmDeleteBot(bot) },
            onAddBot = { addBotModal.show() },
        )
        settingsPage = SettingsPageController(
            root = findViewById(R.id.page_settings),
            repository = repository,
            controller = controller,
            toast = toastHost,
            onReset = { confirmReset() },
            onGetCode = { controller.ensureConnected() },
        )
        chatDetail = ChatDetailController(
            container = chatDetailContainer,
            repository = repository,
            controller = controller,
            toast = toastHost,
            onStartCall = { startVoiceCall() },
        )
    }

    private fun bindModals() {
        activationModal = ActivationModal(modalHost, controller)
        addBotModal = AddBotModal(modalHost, repository, controller, toastHost) {
            contactsPage.render()
            chatPage.render()
        }
    }

    private fun bindTabs() {
        findViewById<View>(R.id.tab_chat).setOnClickListener { switchTab(Tab.CHAT) }
        findViewById<View>(R.id.tab_contacts).setOnClickListener { switchTab(Tab.CONTACTS) }
        findViewById<View>(R.id.tab_settings).setOnClickListener { switchTab(Tab.SETTINGS) }
    }

    // ---------------- Controller 回调 ----------------

    private fun bindController() {
        controller.onConnectionStatusChanged = { status ->
            updateStatus(status)
            chatDetail.onConnectionStatusChanged(status)
        }
        controller.onChatMessage = { botId, message ->
            chatDetail.onChatMessage(botId, message)
            chatPage.render()
        }
        controller.onActivationCodeRequired = { code ->
            activationModal.show(code)
        }
        controller.onActivationCompleted = {
            activationModal.onActivated()
        }
        controller.onError = { message ->
            activationModal.onError()
            toastHost.show(message, ToastHost.Kind.ERROR)
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

    /**
     * 数据变更 → 实时刷新底部未读角标与会话列表。
     *
     * updateUnreadBadge 此前只在 onResume 的 renderAll 里执行，导致打开对话
     * 清零未读（clearUnread）或新消息累加未读后，角标要等下一次 onResume 才更新。
     * 订阅 repository.onDataChanged 后，任何数据变更都立即反映到 UI。
     *
     * 注意：appendMessage → persist → onDataChanged 发生在 OkHttp 工作线程（消息落库），
     * clearUnread 则在主线程（用户点击），必须统一切回 UI 线程。
     */
    private fun bindRepository() {
        repository.onDataChanged = {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateUnreadBadge()
                chatPage.render()
            }
        }
    }

    // ---------------- Tab 切换 ----------------

    private fun switchTab(tab: Tab) {
        // 详情页是内容区最上层的不透明覆盖层：不先关闭的话，切换后的新页面会被它遮挡，
        // 用户感知为「Tab 点击无响应」。详情页打开时点击当前 Tab 也执行关闭，回到该 Tab 列表。
        if (chatDetail.isOpen) {
            chatDetail.close()
            hideIme()
        }
        if (currentTab == tab) return
        currentTab = tab

        findViewById<View>(R.id.page_chat).visibility =
            if (tab == Tab.CHAT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.page_contacts).visibility =
            if (tab == Tab.CONTACTS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.page_settings).visibility =
            if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE

        updateTabIndicator()
        when (tab) {
            Tab.CHAT -> chatPage.render()
            Tab.CONTACTS -> contactsPage.render()
            Tab.SETTINGS -> settingsPage.render()
        }
    }

    private fun updateTabIndicator() {
        val activeColor = ContextCompat.getColor(this, R.color.xz_primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.xz_text_hint)

        fun setTab(tabId: Int, iconId: Int, labelId: Int, active: Boolean) {
            val color = if (active) activeColor else inactiveColor
            findViewById<android.widget.ImageView>(iconId).setColorFilter(color)
            findViewById<TextView>(labelId).setTextColor(color)
        }

        setTab(R.id.tab_chat, R.id.tab_chat_icon, R.id.tab_chat_label, currentTab == Tab.CHAT)
        setTab(R.id.tab_contacts, R.id.tab_contacts_icon, R.id.tab_contacts_label, currentTab == Tab.CONTACTS)
        setTab(R.id.tab_settings, R.id.tab_settings_icon, R.id.tab_settings_label, currentTab == Tab.SETTINGS)
    }

    /** 收起软键盘（详情页输入框可能持有焦点，关闭详情后键盘会残留） */
    private fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = (currentFocus ?: window.decorView).windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }

    // ---------------- 状态与渲染 ----------------

    private fun updateStatus(status: ConnectionStatus) {
        val (textRes, colorRes) = when (status) {
            ConnectionStatus.CONNECTED -> R.string.status_connected to R.color.xz_status_connected
            ConnectionStatus.CONNECTING -> R.string.status_connecting to R.color.xz_status_connecting
            ConnectionStatus.DISCONNECTED -> R.string.status_disconnected to R.color.xz_status_disconnected
            ConnectionStatus.ERROR -> R.string.status_error to R.color.xz_status_error
        }
        findViewById<TextView>(R.id.status_text).setText(textRes)
        findViewById<TextView>(R.id.status_text)
            .setTextColor(ContextCompat.getColor(this, colorRes))
        (findViewById<View>(R.id.status_dot).background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(ContextCompat.getColor(this, colorRes))
    }

    private fun renderAll() {
        chatPage.render()
        contactsPage.render()
        settingsPage.render()
        updateTabIndicator()
        updateUnreadBadge()
    }

    private fun updateUnreadBadge() {
        val total = repository.totalUnread()
        val badge = findViewById<TextView>(R.id.tab_chat_badge)
        if (total > 0) {
            badge.visibility = View.VISIBLE
            badge.text = if (total > 99) getString(R.string.chat_unread_overflow) else total.toString()
        } else {
            badge.visibility = View.GONE
        }
    }

    // ---------------- 交互 ----------------

    private fun confirmDeleteBot(bot: org.oxff.helloxiaozhi.data.Bot) {
        ConfirmModal(
            modalHost = modalHost,
            title = getString(R.string.bot_delete_title),
            desc = getString(R.string.bot_delete_desc, bot.name),
            confirmText = getString(R.string.bot_delete_confirm),
        ) {
            if (repository.removeBot(bot.id)) {
                toastHost.show(getString(R.string.toast_bot_deleted, bot.name), ToastHost.Kind.SUCCESS)
                contactsPage.render()
                chatPage.render()
            } else {
                toastHost.show(getString(R.string.toast_last_bot), ToastHost.Kind.ERROR)
            }
        }.show()
    }

    private fun confirmReset() {
        ConfirmModal(
            modalHost = modalHost,
            title = getString(R.string.reset_title),
            desc = getString(R.string.reset_desc),
            confirmText = getString(R.string.reset_confirm),
        ) {
            repository.resetAll()
            controller.config.clear()
            controller.applySettings()
            toastHost.show(getString(R.string.toast_reset_done), ToastHost.Kind.SUCCESS)
            renderAll()
            // 重置后回到首启状态：默认打开 seed 机器人的对话
            repository.defaultBot()?.let { chatDetail.open(it.id) }
        }.show()
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
            toastHost.show(
                getString(R.string.permission_record_audio_required),
                ToastHost.Kind.ERROR,
            )
        }
    }

    /** 启动录音后进入通话页（对应 App.vue showVoiceCallPanel） */
    private fun enterVoiceCall() {
        controller.startVoiceCall()
        startActivity(Intent(this, VoiceCallActivity::class.java))
    }

    /** 返回键分级：模态 → 对话详情 → 默认 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            modalHost.isShowing -> modalHost.dismiss()
            chatDetail.isOpen -> chatDetail.close()
            else -> super.onBackPressed()
        }
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 100
    }
}
