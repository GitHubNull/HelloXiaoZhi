package org.oxff.helloxiaozhi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
import org.oxff.helloxiaozhi.util.OrientationPolicy
import org.oxff.helloxiaozhi.wake.WakeWordService

/**
 * 三 Tab 外壳（对应设计稿 index.html）：
 * 聊天/通讯录/设置三个 Tab + 对话详情滑入层 + 模态框/Toast 宿主。
 * 小屏空间优化后顶部导航栏已移除，连接状态由 Tab 栏聊天图标上的状态圆点指示。
 *
 * 所有 controller 回调集中在此绑定，再分发给各页面控制器——
 * 避免多个页面争抢单槽回调导致后绑定者胜出、先绑定者静默失效。
 *
 * 重构后：将 Tab 切换逻辑委托给 TabManager。
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

    private lateinit var tabManager: TabManager

    override fun onCreate(savedInstanceState: Bundle?) {
        // 原生横屏小面板（脸屏类真机）显式请求横屏，避开厂商 ROM 强开传感器旋转
        OrientationPolicy.lockIfNeeded(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = application as XiaoZhiApp
        controller = app.controller
        repository = app.repository

        tabManager = TabManager(this)

        bindViews()
        bindPages()
        bindModals()
        bindController()
        bindTabs()
        bindRepository()

        // 处理从语音通话页返回的 Intent
        handleIntent(intent)

        // 首次启动：默认打开唤醒目标机器人的对话
        if (savedInstanceState == null) {
            repository.defaultBot()?.let { chatDetail.open(it.id) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 处理 Intent：从语音通话页返回时打开聊天详情页
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHAT_DETAIL, false) == true) {
            // 切换到聊天 Tab 并打开当前激活机器人的对话详情
            switchTab(TabManager.Tab.CHAT)
            controller.activeBotId?.let { botId ->
                chatDetail.open(botId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controller.ensureConnected()
        renderAll()
        // 若唤醒词检测已开启但权限被回收，自动停止服务
        if (controller.config.wakeWordEnabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            controller.config.wakeWordEnabled = false
            WakeWordService.stop(this)
        }
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
            onGoContacts = { switchTab(TabManager.Tab.CONTACTS) },
        )
        contactsPage = ContactsPageController(
            root = findViewById(R.id.page_contacts),
            repository = repository,
            onOpenChat = { botId ->
                controller.switchActiveBot(botId)
                switchTab(TabManager.Tab.CHAT)
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
        tabManager.bindTabs { tab -> switchTab(tab) }
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

    private fun switchTab(tab: TabManager.Tab) {
        tabManager.switchTab(
            tab = tab,
            onTabChanged = { renderTab(it) },
            shouldCloseDetail = { chatDetail.isOpen },
            onCloseDetail = { chatDetail.close() },
        )
    }

    private fun renderTab(tab: TabManager.Tab) {
        when (tab) {
            TabManager.Tab.CHAT -> chatPage.render()
            TabManager.Tab.CONTACTS -> contactsPage.render()
            TabManager.Tab.SETTINGS -> settingsPage.render()
        }
    }

    // ---------------- 状态与渲染 ----------------

    /**
     * 连接状态指示：顶部导航栏移除后，改由 Tab 栏聊天图标上的状态圆点变色。
     * 圆点与未读角标分居图标两角（角标 top|end 左移 10dp 避让），不会互相遮挡。
     */
    private fun updateStatus(status: ConnectionStatus) {
        val colorRes = when (status) {
            ConnectionStatus.CONNECTED -> R.color.xz_status_connected
            ConnectionStatus.CONNECTING -> R.color.xz_status_connecting
            ConnectionStatus.DISCONNECTED -> R.color.xz_status_disconnected
            ConnectionStatus.ERROR -> R.color.xz_status_error
        }
        (findViewById<View>(R.id.tab_status_dot).background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(ContextCompat.getColor(this, colorRes))
    }

    private fun renderAll() {
        chatPage.render()
        contactsPage.render()
        settingsPage.render()
        tabManager.updateTabIndicator()
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
        // 先暂停唤醒词检测，避免麦克风冲突
        WakeWordService.pause(this)
        // 确保 WebSocket 已连接，未连接时先连接再启动通话
        if (controller.connectionStatus != org.oxff.helloxiaozhi.chat.ConnectionStatus.CONNECTED) {
            controller.ensureConnected()
        }
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

    companion object {
        private const val REQUEST_RECORD_AUDIO = 100
        /** Intent extra：打开聊天详情页（从语音通话页返回时） */
        const val EXTRA_OPEN_CHAT_DETAIL = "extra_open_chat_detail"
    }
}
