package org.oxff.helloxiaozhi.ui

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R

/**
 * Tab 管理器：负责 Tab 切换逻辑。
 *
 * 职责：
 *  - 管理 Tab 切换状态
 *  - 更新 Tab 指示器样式
 *  - 处理软键盘隐藏
 *
 * 从 MainActivity 拆分而来，专注于 Tab 管理职责。
 */
class TabManager(
    private val activity: Activity,
) {
    enum class Tab { CHAT, CONTACTS, SETTINGS }

    var currentTab = Tab.CHAT
        private set

    private val pageChat: View by lazy { activity.findViewById(R.id.page_chat) }
    private val pageContacts: View by lazy { activity.findViewById(R.id.page_contacts) }
    private val pageSettings: View by lazy { activity.findViewById(R.id.page_settings) }

    /**
     * 切换 Tab
     * @param tab 目标 Tab
     * @param onTabChanged Tab 切换后的回调（用于渲染页面内容）
     * @param shouldCloseDetail 是否需要先关闭详情页的检查函数
     * @param onCloseDetail 关闭详情页的回调
     */
    fun switchTab(
        tab: Tab,
        onTabChanged: (Tab) -> Unit,
        shouldCloseDetail: () -> Boolean,
        onCloseDetail: () -> Unit,
    ) {
        // 详情页是内容区最上层的不透明覆盖层：不先关闭的话，切换后的新页面会被它遮挡，
        // 用户感知为「Tab 点击无响应」。详情页打开时点击当前 Tab 也执行关闭，回到该 Tab 列表。
        if (shouldCloseDetail()) {
            onCloseDetail()
            hideIme()
        }
        if (currentTab == tab) return
        currentTab = tab

        pageChat.visibility = if (tab == Tab.CHAT) View.VISIBLE else View.GONE
        pageContacts.visibility = if (tab == Tab.CONTACTS) View.VISIBLE else View.GONE
        pageSettings.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE

        updateTabIndicator()
        onTabChanged(tab)
    }

    /**
     * 更新 Tab 指示器样式
     */
    fun updateTabIndicator() {
        val activeColor = ContextCompat.getColor(activity, R.color.xz_primary)
        val inactiveColor = ContextCompat.getColor(activity, R.color.xz_text_hint)

        fun setTab(iconId: Int, labelId: Int, active: Boolean) {
            val color = if (active) activeColor else inactiveColor
            activity.findViewById<ImageView>(iconId).setColorFilter(color)
            activity.findViewById<TextView>(labelId).setTextColor(color)
        }

        setTab(R.id.tab_chat_icon, R.id.tab_chat_label, currentTab == Tab.CHAT)
        setTab(R.id.tab_contacts_icon, R.id.tab_contacts_label, currentTab == Tab.CONTACTS)
        setTab(R.id.tab_settings_icon, R.id.tab_settings_label, currentTab == Tab.SETTINGS)
    }

    /**
     * 绑定 Tab 点击事件
     */
    fun bindTabs(onTabSelected: (Tab) -> Unit) {
        activity.findViewById<View>(R.id.tab_chat).setOnClickListener { onTabSelected(Tab.CHAT) }
        activity.findViewById<View>(R.id.tab_contacts).setOnClickListener { onTabSelected(Tab.CONTACTS) }
        activity.findViewById<View>(R.id.tab_settings).setOnClickListener { onTabSelected(Tab.SETTINGS) }
    }

    /** 收起软键盘（详情页输入框可能持有焦点，关闭详情后键盘会残留） */
    private fun hideIme() {
        val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = (activity.currentFocus ?: activity.window.decorView).windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }
}
