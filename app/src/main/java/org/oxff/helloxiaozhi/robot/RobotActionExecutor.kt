package org.oxff.helloxiaozhi.robot

/**
 * 机器人动作执行器接口：抽象硬件操作，使 RobotActionRegistry 可测试。
 *
 * VisbotRobotController 实现此接口；单元测试中可用 mock 替代。
 */
interface RobotActionExecutor {
    // 头部动作
    fun rotateHead(angle: Float, speed: Int)
    fun nodHead(speed: Int)
    fun shakeHead(speed: Int)

    // 移动动作
    fun moveForward(speed: Float, duration: Long)
    fun moveBackward(speed: Float, duration: Long)
    fun turnLeft(speed: Float, angle: Float)
    fun turnRight(speed: Float, angle: Float)
    fun stopMoving()

    // 表情动作
    fun showEmotion(name: String, speaking: Boolean, loops: Int)
    fun dismissEmotion()

    // 组合动作
    fun expressAgreement()
    fun expressDisagreement()
    fun expressCuriosity()
    fun expressExcitement()
    fun expressLove()
    fun expressShyness()
    fun expressSurprise()
    fun waveHello()
    fun dance()
    fun think()
    fun resetToDefault()
}
