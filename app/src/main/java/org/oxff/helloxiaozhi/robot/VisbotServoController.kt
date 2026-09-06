package org.oxff.helloxiaozhi.robot

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ubtrobot.Robot
import com.ubtrobot.servo.RotationOption
import com.ubtrobot.servo.ServoController

/**
 * Visbot 舵机控制器：控制头部舵机旋转（点头、摇头、归中等）。
 *
 * 舵机 ID 固定为 "head"，角度范围 -23° 到 +25°（实测安全范围）。
 * 所有动作通过 Handler 链式执行，避免阻塞主线程。
 */
class VisbotServoController {

    /** 舵机服务是否可用 */
    val isAvailable: Boolean
        get() = servoController != null

    private var servoController: ServoController? = null
    private val handler = Handler(Looper.getMainLooper())

    init {
        try {
            servoController = Robot.globalContext().getSystemService("servo")
            Log.i(TAG, "ServoController initialized: ${servoController != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ServoController", e)
        }
    }

    /**
     * 旋转头部到指定角度
     * @param angle 目标角度（-23° 到 +25°）
     * @param speed 旋转速度（0-100，默认 50）
     */
    fun rotate(angle: Float, speed: Int = DEFAULT_SPEED) {
        val controller = servoController ?: run {
            Log.w(TAG, "ServoController not available")
            return
        }
        val clampedAngle = angle.coerceIn(MIN_ANGLE, MAX_ANGLE)
        try {
            val option = RotationOption.Builder(SERVO_ID)
                .setAngle(clampedAngle)
                .setSpeed(speed.toFloat())
                .setAngleAbsolute(true)
                .build()
            controller.rotate(option)
            Log.i(TAG, "Rotating head to $clampedAngle° at speed $speed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate servo", e)
        }
    }

    /** 点头：先向下 15°，再回中 */
    fun nod() {
        rotate(NOD_ANGLE)
        handler.postDelayed({ rotate(CENTER_ANGLE) }, NOD_DURATION_MS)
    }

    /** 摇头：先向左 -15°，再向右 15°，最后回中 */
    fun shakeHead() {
        rotate(-SHAKE_ANGLE)
        handler.postDelayed({ rotate(SHAKE_ANGLE) }, SHAKE_DURATION_MS)
        handler.postDelayed({ rotate(CENTER_ANGLE) }, SHAKE_DURATION_MS * 2)
    }

    /** 抬头：向上 20° */
    fun lookUp() {
        rotate(LOOK_UP_ANGLE)
    }

    /** 低头：向下 20° */
    fun lookDown() {
        rotate(LOOK_DOWN_ANGLE)
    }

    /** 向左看：向左 -20° */
    fun lookLeft() {
        rotate(-LOOK_SIDE_ANGLE)
    }

    /** 向右看：向右 20° */
    fun lookRight() {
        rotate(LOOK_SIDE_ANGLE)
    }

    /** 归中：回到 0° */
    fun center() {
        rotate(CENTER_ANGLE)
    }

    companion object {
        private const val TAG = "VisbotServoController"

        /** 舵机 ID（Visbot 头部舵机固定为 "head"） */
        private const val SERVO_ID = "head"

        /** 最小安全角度 */
        private const val MIN_ANGLE = -23.0f

        /** 最大安全角度 */
        private const val MAX_ANGLE = 25.0f

        /** 默认旋转速度 */
        private const val DEFAULT_SPEED = 50

        /** 中心角度 */
        private const val CENTER_ANGLE = 0.0f

        /** 点头角度 */
        private const val NOD_ANGLE = 15.0f

        /** 点头持续时间（毫秒） */
        private const val NOD_DURATION_MS = 300L

        /** 摇头角度 */
        private const val SHAKE_ANGLE = 15.0f

        /** 摇头单次持续时间（毫秒） */
        private const val SHAKE_DURATION_MS = 200L

        /** 抬头角度 */
        private const val LOOK_UP_ANGLE = 20.0f

        /** 低头角度 */
        private const val LOOK_DOWN_ANGLE = -20.0f

        /** 侧视角度 */
        private const val LOOK_SIDE_ANGLE = 20.0f
    }
}
