package org.oxff.helloxiaozhi.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.controller.XiaoZhiController
import org.oxff.helloxiaozhi.data.StoredMessage
import org.oxff.helloxiaozhi.ui.call.SmallScreenOptimizer
import org.oxff.helloxiaozhi.ui.call.VoiceCallAnimationController
import org.oxff.helloxiaozhi.ui.call.VoiceCallViewBinder
import org.oxff.helloxiaozhi.ui.view.ToastHost
import org.oxff.helloxiaozhi.wake.WakeWordService

/**
 * 语音通话页（对应设计稿 call.html）：
 * 水波涟漪动画（单球由真实 ChatState 驱动）+ 通话计时 + 历史记录 +
 * 三按钮控制栏（机器人增益 / 挂断 / 你的增益）。
 * 文字/动画视图切换由双击动画区域触发（小屏空间优化移除了顶部切换栏）。
 *
 * 重构后：作为协调者，将具体职责委托给专门组件：
 *  - VoiceCallViewBinder: 视图绑定与初始化
 *  - VoiceCallAnimationController: 通话动画控制
 *  - SmallScreenOptimizer: 小屏专属优化
 */
class VoiceCallActivity : AppCompatActivity() {

    private lateinit var controller: XiaoZhiController
    private lateinit var viewBinder: VoiceCallViewBinder
    private lateinit var animationController: VoiceCallAnimationController
    private lateinit var smallScreenOptimizer: SmallScreenOptimizer

    private val mainHandler = Handler(Looper.getMainLooper())

    private var autoStartCall = false // 是否自动开始通话（唤醒场景）

    // 完整界面下双击动画区域切换文字/动画（替代已移除的顶部切换栏）
    private var expandedTapCount = 0
    private var lastExpandedTapMs = 0L
    private val middleRect = android.graphics.Rect()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 方向由 manifest screenOrientation="behind" 继承 MainActivity
        // （MainActivity 已通过 OrientationPolicy 在小屏原生横屏真机上锁定 landscape），
        // 避免在 onCreate 中动态 setRequestedOrientation 触发「窗口创建→配置变更」两阶段旋转动画。
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_call)
        controller = (application as XiaoZhiApp).controller

        // 处理 Intent extra：唤醒场景自动开始通话
        intent?.let {
            autoStartCall = it.getBooleanExtra(EXTRA_AUTO_START_CALL, false)
            val targetBotId = it.getStringExtra(EXTRA_BOT_ID)
            if (!targetBotId.isNullOrBlank() && controller.activeBotId != targetBotId) {
                controller.switchActiveBot(targetBotId)
            }
        }

        // 初始化组件
        viewBinder = VoiceCallViewBinder(this, controller)
        viewBinder.bindViews()

        animationController = VoiceCallAnimationController(
            rippleView = viewBinder.rippleView,
            callState = viewBinder.callState,
            callTimer = viewBinder.callTimer,
            animPanel = viewBinder.animPanel,
            historyList = viewBinder.historyList,
        )

        smallScreenOptimizer = SmallScreenOptimizer(
            activity = this,
            statusBlock = viewBinder.statusBlock,
            bottomBar = viewBinder.bottomBar,
            middleArea = viewBinder.middleArea,
            poolFrame = viewBinder.poolFrame,
            countdownOverlay = viewBinder.countdownOverlay,
            countdownText = viewBinder.countdownText,
            rippleView = viewBinder.rippleView,
        ).apply {
            sidePadPx = viewBinder.sidePadPx
            poolPadPx = viewBinder.poolPadPx
            poolBg = viewBinder.poolBg
        }

        bindController()
        startCall()
        if (smallScreenOptimizer.isSmallScreen) smallScreenOptimizer.setupSmallScreenMode()

        // 通话期间暂停唤醒词检测，避免麦克风冲突
        WakeWordService.pause(this)
    }

    override fun onResume() {
        super.onResume()
        viewBinder.rippleView.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 从唤醒服务/助手再次启动时，若已在通话中则复用当前界面
        setIntent(intent)
        intent.let {
            val targetBotId = it.getStringExtra(EXTRA_BOT_ID)
            if (!targetBotId.isNullOrBlank() && controller.activeBotId != targetBotId) {
                controller.switchActiveBot(targetBotId)
            }
            if (it.getBooleanExtra(EXTRA_AUTO_START_CALL, false) && !animationController.isCallStarted()) {
                autoStartCall = true
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 锁屏 / 退后台即停动画，避免烧 CPU
        viewBinder.rippleView.stop()
    }

    override fun onDestroy() {
        // 无论通话界面以何种方式销毁（挂断/返回/系统回收/最近任务划掉），
        // 都必须恢复唤醒词检测：否则非 hangUp 路径退出时，WakeWordService
        // 会永远停在 pause 状态（麦克风被释放），此后用户再也无法唤醒。
        WakeWordService.resume(this)
        unbindController()
        animationController.stopTimer()
        smallScreenOptimizer.cleanup()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        hangUp()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 纯动画界面下整个窗口即动画区域：在窗口最上游捕获点击，
        // 不依赖子 View 命中测试，连点 2 次恢复完整界面、3 次挂断回主页。
        // 完整界面下双击动画/文字区域切换文字/动画；只计数不拦截事件，
        // 增益弹层收起等子 View 点击不受影响。
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (smallScreenOptimizer.isCollapsed()) {
                if (smallScreenOptimizer.onCollapsedTap()) {
                    hangUp()
                }
            } else {
                onExpandedTap(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 完整界面双击切换文字/动画：仅响应 middle_area 内的点击，避免误触底部控制栏按钮 */
    private fun onExpandedTap(ev: MotionEvent) {
        viewBinder.middleArea.getGlobalVisibleRect(middleRect)
        if (!middleRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
            expandedTapCount = 0
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastExpandedTapMs > TAP_WINDOW_MS) expandedTapCount = 0
        lastExpandedTapMs = now
        expandedTapCount++
        if (expandedTapCount >= 2) {
            expandedTapCount = 0
            animationController.toggleView()
        }
    }

    // ---------------- Controller 回调 ----------------

    private var pendingAutoStart = false // 等待连接建立后自动开始通话

    private fun bindController() {
        controller.onChatStateChanged = { state -> animationController.updateCallState(state) }
        controller.onChatMessage = { _, message ->
            // 通话页只展示当前机器人的消息（通话期间 activeBotId 不变）
            viewBinder.historyAdapter.add(
                StoredMessage(
                    role = message.role,
                    content = message.content,
                    ts = System.currentTimeMillis(),
                ),
            )
            viewBinder.historyList.scrollToPosition(viewBinder.historyAdapter.itemCount - 1)
        }
        controller.onUserWaveLevel = { level ->
            // 用户说话电平驱动用户球体水波动画
            viewBinder.rippleView.setUserAudioIntensity(level)
        }
        controller.onAiWaveLevel = { level ->
            // AI 说话电平驱动 AI 球体水波动画
            viewBinder.rippleView.setAiAudioIntensity(level)
        }
        controller.onError = { message ->
            viewBinder.toastHost.show(message, ToastHost.Kind.ERROR)
        }
        // 连接状态变更：唤醒场景下等待连接建立后自动开始通话
        controller.onConnectionStatusChanged = { status ->
            if (status == org.oxff.helloxiaozhi.chat.ConnectionStatus.CONNECTED && pendingAutoStart) {
                pendingAutoStart = false
                controller.startVoiceCall()
            }
        }
        animationController.updateCallState(controller.chatState)
    }

    private fun unbindController() {
        controller.onChatStateChanged = null
        controller.onChatMessage = null
        controller.onUserWaveLevel = null
        controller.onAiWaveLevel = null
        controller.onError = null
        controller.onConnectionStatusChanged = null
    }

    // ---------------- 通话控制 ----------------

    private fun startCall() {
        // 对应设计稿 call.js：1.2s 后接通；计时器必须在 callStarted 置位后才启动，
        // 否则首次执行命中 if (!callStarted) return 后不再自我投递，计时链永久中断（显示恒为 0）
        animationController.startCall {
            viewBinder.toastHost.show(getString(R.string.call_connected), ToastHost.Kind.SUCCESS, 1500)
            animationController.updateCallState(controller.chatState)
            // 唤醒场景：自动开始语音通话（无需用户点击）
            if (autoStartCall) {
                // 若已连接则直接开始，否则等待连接建立
                if (controller.connectionStatus == org.oxff.helloxiaozhi.chat.ConnectionStatus.CONNECTED) {
                    controller.startVoiceCall()
                } else {
                    pendingAutoStart = true
                    controller.ensureConnected()
                }
            }
        }
    }

    /** 挂断：停止采集并退出（对应 App.vue closeVoiceCallPanel） */
    fun hangUp() {
        controller.stopVoiceCall()
        // 机器人复位：归中头部、停止移动、消除表情
        controller.robotController.resetToDefault()
        viewBinder.toastHost.show(getString(R.string.call_finished), ToastHost.Kind.NORMAL, 1200)
        // 挂断后恢复唤醒词检测
        WakeWordService.resume(this)
        mainHandler.postDelayed({ finish() }, 900)
    }

    companion object {
        // 连点窗口放宽到 2s：小屏真机注入/操作间隔偏大，过严会导致连点永远不成立
        const val TAP_WINDOW_MS = 2000L

        /** Intent extra：目标机器人 ID（唤醒场景指定） */
        const val EXTRA_BOT_ID = "extra_bot_id"

        /** Intent extra：是否自动开始通话（唤醒场景为 true） */
        const val EXTRA_AUTO_START_CALL = "extra_auto_start_call"
    }
}
