package org.oxff.helloxiaozhi.robot

import android.util.Log
import com.ubtrobot.Robot
import com.ubtrobot.locomotion.LocomotionController
import com.ubtrobot.locomotion.LocomotionOption

/**
 * Visbot 电机控制器：控制机器人移动（前进、后退、左转、右转、停止）。
 *
 * 通过 LocomotionController 与系统服务通信，所有参数经过安全范围限制。
 */
class VisbotMotionController {

    /** 电机服务是否可用 */
    val isAvailable: Boolean
        get() = locomotionController != null

    private var locomotionController: LocomotionController? = null

    init {
        try {
            locomotionController = Robot.globalContext().getSystemService("locomotion")
            Log.i(TAG, "LocomotionController initialized: ${locomotionController != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get LocomotionController", e)
        }
    }

    /**
     * 执行电机运动
     * @param option 运动选项
     */
    private fun locomote(option: LocomotionOption) {
        val controller = locomotionController ?: run {
            Log.w(TAG, "LocomotionController not available")
            return
        }
        try {
            Log.i(TAG, "Calling locomote with option: $option")
            val promise = controller.locomote(option)
            Log.i(TAG, "Locomote promise created: ${promise != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to locomote", e)
        }
    }

    /** 前进（默认速度 0.3，持续 1 秒） */
    fun moveForward(speed: Float = DEFAULT_SPEED, duration: Long = DEFAULT_DURATION_MS) {
        val safeSpeed = speed.coerceIn(0.0f, MAX_SPEED)
        val option = LocomotionOption.Builder()
            .setMovingSpeed(safeSpeed)
            .setMovingAngle(0f)
            .setDuration(duration)
            .build()
        locomote(option)
    }

    /** 后退（默认速度 0.3，持续 1 秒） */
    fun moveBackward(speed: Float = DEFAULT_SPEED, duration: Long = DEFAULT_DURATION_MS) {
        val safeSpeed = speed.coerceIn(0.0f, MAX_SPEED)
        val option = LocomotionOption.Builder()
            .setMovingSpeed(-safeSpeed)
            .setMovingAngle(0f)
            .setDuration(duration)
            .build()
        locomote(option)
    }

    /** 左转（默认 90°，速度 30°/s） */
    fun turnLeft(speed: Float = DEFAULT_TURN_SPEED, angle: Float = DEFAULT_TURN_ANGLE) {
        val safeSpeed = speed.coerceIn(0.0f, MAX_TURN_SPEED)
        val safeAngle = angle.coerceIn(0.0f, MAX_TURN_ANGLE)
        val option = LocomotionOption.Builder()
            .setTurningSpeed(safeSpeed)
            .setTurningAngle(safeAngle)
            .setTurningAxis(LocomotionOption.TURNING_AXIS_CENTER)
            .build()
        locomote(option)
    }

    /** 右转（默认 90°，速度 30°/s） */
    fun turnRight(speed: Float = DEFAULT_TURN_SPEED, angle: Float = DEFAULT_TURN_ANGLE) {
        val safeSpeed = speed.coerceIn(0.0f, MAX_TURN_SPEED)
        val safeAngle = angle.coerceIn(0.0f, MAX_TURN_ANGLE)
        val option = LocomotionOption.Builder()
            .setTurningSpeed(-safeSpeed)
            .setTurningAngle(safeAngle)
            .setTurningAxis(LocomotionOption.TURNING_AXIS_CENTER)
            .build()
        locomote(option)
    }

    /** 紧急停止 */
    fun stop() {
        val option = LocomotionOption.Builder()
            .setMovingSpeed(0f)
            .setTurningSpeed(0f)
            .setEmergency(true)
            .build()
        locomote(option)
    }

    companion object {
        private const val TAG = "VisbotMotionController"

        /** 默认移动速度（0-1） */
        private const val DEFAULT_SPEED = 0.3f

        /** 最大移动速度 */
        private const val MAX_SPEED = 1.0f

        /** 默认持续时间（毫秒） */
        private const val DEFAULT_DURATION_MS = 1000L

        /** 默认转向速度（度/秒） */
        private const val DEFAULT_TURN_SPEED = 30.0f

        /** 最大转向速度（度/秒） */
        private const val MAX_TURN_SPEED = 90.0f

        /** 默认转向角度（度） */
        private const val DEFAULT_TURN_ANGLE = 90.0f

        /** 最大转向角度（度） */
        private const val MAX_TURN_ANGLE = 360.0f
    }
}
