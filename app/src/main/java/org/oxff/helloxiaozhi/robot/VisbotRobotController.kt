package org.oxff.helloxiaozhi.robot

import android.content.Context
import android.util.Log
import com.ubtrobot.Robot

/**
 * Visbot 机器人总控：统一初始化 SDK、检测可用性、暴露高层动作/表情 API。
 *
 * 所有方法在非 Visbot 设备上静默跳过（isAvailable=false），不影响其他设备正常运行。
 */
class VisbotRobotController(private val context: Context) {

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
    fun stopMoving() {
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
    fun dismissEmotion() {
        if (!isAvailable) return
        emotionController.dismiss()
    }

    // ---------------- 组合动作 ----------------

    /** 表达肯定：点头 + 开心表情 */
    fun expressAgreement() {
        if (!isAvailable) return
        nod()
        showEmotion("happy", speaking = false)
    }

    /** 表达否定：摇头 + 警觉表情 */
    fun expressDisagreement() {
        if (!isAvailable) return
        shakeHead()
        showEmotion("alert", speaking = false)
    }

    /** 表达疑问：歪头 + 好奇表情 */
    fun expressCuriosity() {
        if (!isAvailable) return
        lookLeft()
        showEmotion("curious", speaking = false)
    }

    /** 表达开心：点头 + 兴奋表情 */
    fun expressExcitement() {
        if (!isAvailable) return
        nod()
        showEmotion("excited", speaking = false)
    }

    /** 表达爱意：爱心表情 + 轻轻点头 */
    fun expressLove() {
        if (!isAvailable) return
        showEmotion("love", speaking = false)
        nod()
    }

    /** 表达害羞：害羞表情 + 低头 */
    fun expressShyness() {
        if (!isAvailable) return
        showEmotion("shy", speaking = false)
        lookDown()
    }

    /** 表达惊讶：惊讶表情 + 抬头 */
    fun expressSurprise() {
        if (!isAvailable) return
        showEmotion("surprised", speaking = false)
        lookUp()
    }

    /** 重置到默认状态：归中 + 停止移动 + 默认表情 */
    fun resetToDefault() {
        if (!isAvailable) return
        centerHead()
        stopMoving()
        showEmotion("default", speaking = false)
    }

    companion object {
        private const val TAG = "VisbotRobotController"
    }
}
