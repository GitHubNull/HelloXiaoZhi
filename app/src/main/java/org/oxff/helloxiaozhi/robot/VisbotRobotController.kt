package org.oxff.helloxiaozhi.robot

import android.content.Context
import android.util.Log
import com.ubtrobot.Robot

/**
 * Visbot 机器人总控：统一初始化 SDK、检测可用性、暴露高层动作/表情 API。
 *
 * 所有方法在非 Visbot 设备上静默跳过（isAvailable=false），不影响其他设备正常运行。
 */
class VisbotRobotController(private val context: Context) : RobotActionExecutor {

    /** 当前设备是否为 Visbot 机器人（rosa.jar SDK 可用） */
    val isAvailable: Boolean
        get() = _isAvailable

    private var _isAvailable = false

    /** 舵机控制器（延迟初始化，确保 Robot SDK 已初始化） */
    val servoController: VisbotServoController by lazy { VisbotServoController() }

    /** 电机控制器（延迟初始化） */
    val motionController: VisbotMotionController by lazy { VisbotMotionController() }

    /** 表情控制器（延迟初始化） */
    val emotionController: VisbotEmotionController by lazy { VisbotEmotionController() }

    init {
        // 非 Visbot 设备跳过 SDK 初始化：见 initSdk()
        initSdk()
    }

    /** 初始化 SDK 并检测可用性（仅 Visbot 设备执行完整逻辑） */
    private fun initSdk() {
        // rosa.jar 的 Robot.initialize() 会在后台创建 AutoReconnectConnection 线程，
        // 持续尝试连接 com.ubtrobot.provider.master；普通设备上该服务不存在，
        // 导致 MST 刷屏与无意义的后台重连。这里在初始化前先轻量探测设备，
        // 缺失则直接短路，不调用 SDK、不引入副作用。
        if (!isVisbotDevice()) {
            Log.i(TAG, "Not a Visbot device, skip Robot SDK init")
            return
        }
        try {
            Robot.initialize(context.applicationContext)
            Log.i(TAG, "Visbot Robot SDK initialized")
            // 初始化完成后检测可用性（显式访问所有控制器以触发 lazy 初始化）
            val servoOk = servoController.isAvailable
            val motionOk = motionController.isAvailable
            val emotionOk = emotionController.isAvailable
            _isAvailable = servoOk || motionOk || emotionOk
            Log.i(TAG, "Visbot Robot available: $_isAvailable (servo=$servoOk, motion=$motionOk, emotion=$emotionOk)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Visbot Robot SDK", e)
            _isAvailable = false
        }
    }

    /**
     * 是否为 Visbot 机器人设备：探测 Master ContentProvider 是否存在。
     * 仅查 PackageManager（解析 authority），不启动服务进程、不触发 Provider 创建，
     * 与 rosa.jar 连接使用的 authority 一致（ConnectMasterSideConnection#AUTHORITY）。
     */
    @Suppress("DEPRECATION") // resolveContentProvider(String,int) 在 API 33+ 有新 PackageInfoFlags 重载
    private fun isVisbotDevice(): Boolean = runCatching {
        context.packageManager.resolveContentProvider(MASTER_PROVIDER_AUTHORITY, 0) != null
    }.getOrDefault(false)

    // ---------------- 舵机动作 ----------------

    /** 点头（肯定/同意） */
    fun nod() {
        if (!isAvailable) return
        servoController.nod()
    }

    /** 摇头（否定/拒绝） */
    fun shakeHead() {
        if (!isAvailable) return
        servoController.shakeHead()
    }

    /** 抬头 */
    fun lookUp() {
        if (!isAvailable) return
        servoController.lookUp()
    }

    /** 低头 */
    fun lookDown() {
        if (!isAvailable) return
        servoController.lookDown()
    }

    /** 向左看 */
    fun lookLeft() {
        if (!isAvailable) return
        servoController.lookLeft()
    }

    /** 向右看 */
    fun lookRight() {
        if (!isAvailable) return
        servoController.lookRight()
    }

    /** 头部归中 */
    fun centerHead() {
        if (!isAvailable) return
        servoController.center()
    }

    // ---------------- 电机动作 ----------------

    /** 前进 */
    fun moveForward() {
        if (!isAvailable) return
        motionController.moveForward()
    }

    /** 后退 */
    fun moveBackward() {
        if (!isAvailable) return
        motionController.moveBackward()
    }

    /** 左转 */
    fun turnLeft() {
        if (!isAvailable) return
        motionController.turnLeft()
    }

    /** 右转 */
    fun turnRight() {
        if (!isAvailable) return
        motionController.turnRight()
    }

    /** 停止移动 */
    override fun stopMoving() {
        if (!isAvailable) return
        motionController.stop()
    }

    // ---------------- 表情 ----------------

    /**
     * 显示表情
     * @param name 表情名称（happy, excited, surprised, love, shy, curious, alert, confidence, enjoy, funny, default, lookup, lookdown, lookleft, lookright）
     * @param speaking 是否正在说话（决定使用 speak_ 还是 silent_ 前缀）
     */
    fun showEmotion(name: String, speaking: Boolean = false) {
        if (!isAvailable) return
        emotionController.showEmotion(name, speaking)
    }

    /** 消除当前表情 */
    override fun dismissEmotion() {
        if (!isAvailable) return
        emotionController.dismiss()
    }

    // ---------------- 组合动作（实现 RobotActionExecutor 接口） ----------------

    /** 表达肯定：点头 + 开心表情 */
    override fun expressAgreement() {
        if (!isAvailable) return
        nod()
        showEmotion("happy", speaking = false)
    }

    /** 表达否定：摇头 + 警觉表情 */
    override fun expressDisagreement() {
        if (!isAvailable) return
        shakeHead()
        showEmotion("alert", speaking = false)
    }

    /** 表达疑问：歪头 + 好奇表情 */
    override fun expressCuriosity() {
        if (!isAvailable) return
        lookLeft()
        showEmotion("curious", speaking = false)
    }

    /** 表达开心：点头 + 兴奋表情 */
    override fun expressExcitement() {
        if (!isAvailable) return
        nod()
        showEmotion("excited", speaking = false)
    }

    /** 表达爱意：爱心表情 + 轻轻点头 */
    override fun expressLove() {
        if (!isAvailable) return
        showEmotion("love", speaking = false)
        nod()
    }

    /** 表达害羞：害羞表情 + 低头 */
    override fun expressShyness() {
        if (!isAvailable) return
        showEmotion("shy", speaking = false)
        lookDown()
    }

    /** 表达惊讶：惊讶表情 + 抬头 */
    override fun expressSurprise() {
        if (!isAvailable) return
        showEmotion("surprised", speaking = false)
        lookUp()
    }

    /** 重置到默认状态：归中 + 停止移动 + 默认表情 */
    override fun resetToDefault() {
        if (!isAvailable) return
        centerHead()
        stopMoving()
        showEmotion("default", speaking = false)
    }

    /** 打招呼：右转 45° + 开心表情 + 回正 */
    override fun waveHello() {
        if (!isAvailable) return
        showEmotion("happy", speaking = false)
        turnRight()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            centerHead()
        }, 800)
    }

    /** 庆祝舞蹈：左右转 + 兴奋表情 */
    override fun dance() {
        if (!isAvailable) return
        showEmotion("excited", speaking = false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        turnLeft()
        handler.postDelayed({ turnRight() }, 400)
        handler.postDelayed({ turnLeft() }, 800)
        handler.postDelayed({ turnRight() }, 1200)
        handler.postDelayed({ centerHead() }, 1600)
    }

    /** 思考姿态：低头 + 好奇表情 */
    override fun think() {
        if (!isAvailable) return
        lookDown()
        showEmotion("curious", speaking = false)
    }

    /** 多次点头 */
    fun nodTimes(times: Int) {
        if (!isAvailable) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        repeat(times) { i ->
            handler.postDelayed({ servoController.nod() }, i * 500L)
        }
    }

    /** 多次摇头 */
    fun shakeHeadTimes(times: Int) {
        if (!isAvailable) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        repeat(times) { i ->
            handler.postDelayed({ servoController.shakeHead() }, i * 600L)
        }
    }

    // ---------------- RobotActionExecutor 接口实现（带参数版本） ----------------

    override fun rotateHead(angle: Float, speed: Int) {
        if (!isAvailable) return
        servoController.rotate(angle, speed)
    }

    override fun nodHead(speed: Int) {
        if (!isAvailable) return
        servoController.rotate(15f, speed)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { servoController.rotate(0f, speed) }, 300
        )
    }

    override fun shakeHead(speed: Int) {
        if (!isAvailable) return
        servoController.rotate(-15f, speed)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({ servoController.rotate(15f, speed) }, 200)
        handler.postDelayed({ servoController.rotate(0f, speed) }, 400)
    }

    override fun moveForward(speed: Float, duration: Long) {
        if (!isAvailable) return
        motionController.moveForward(speed, duration)
    }

    override fun moveBackward(speed: Float, duration: Long) {
        if (!isAvailable) return
        motionController.moveBackward(speed, duration)
    }

    override fun turnLeft(speed: Float, angle: Float) {
        if (!isAvailable) return
        motionController.turnLeft(speed, angle)
    }

    override fun turnRight(speed: Float, angle: Float) {
        if (!isAvailable) return
        motionController.turnRight(speed, angle)
    }

    override fun showEmotion(name: String, speaking: Boolean, loops: Int) {
        if (!isAvailable) return
        emotionController.showEmotion(name, speaking, loops = loops)
    }

    companion object {
        private const val TAG = "VisbotRobotController"

        /** rosa.jar 连接 Master 服务的 ContentProvider authority（用于探测 Visbot 设备） */
        private const val MASTER_PROVIDER_AUTHORITY = "com.ubtrobot.provider.master"
    }
}
