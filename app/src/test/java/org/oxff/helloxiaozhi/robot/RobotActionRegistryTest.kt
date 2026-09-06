package org.oxff.helloxiaozhi.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RobotActionRegistry 单元测试：动作注册、查找、执行与 tools/list JSON 生成。
 */
class RobotActionRegistryTest {

    /** 记录调用的 mock 执行器 */
    private class RecordingExecutor : RobotActionExecutor {
        val calls = mutableListOf<String>()

        override fun rotateHead(angle: Float, speed: Int) { calls.add("rotateHead($angle,$speed)") }
        override fun nodHead(speed: Int) { calls.add("nodHead($speed)") }
        override fun shakeHead(speed: Int) { calls.add("shakeHead($speed)") }
        override fun moveForward(speed: Float, duration: Long) { calls.add("moveForward($speed,$duration)") }
        override fun moveBackward(speed: Float, duration: Long) { calls.add("moveBackward($speed,$duration)") }
        override fun turnLeft(speed: Float, angle: Float) { calls.add("turnLeft($speed,$angle)") }
        override fun turnRight(speed: Float, angle: Float) { calls.add("turnRight($speed,$angle)") }
        override fun stopMoving() { calls.add("stopMoving") }
        override fun showEmotion(name: String, speaking: Boolean, loops: Int) { calls.add("showEmotion($name,$speaking,$loops)") }
        override fun dismissEmotion() { calls.add("dismissEmotion") }
        override fun expressAgreement() { calls.add("expressAgreement") }
        override fun expressDisagreement() { calls.add("expressDisagreement") }
        override fun expressCuriosity() { calls.add("expressCuriosity") }
        override fun expressExcitement() { calls.add("expressExcitement") }
        override fun expressLove() { calls.add("expressLove") }
        override fun expressShyness() { calls.add("expressShyness") }
        override fun expressSurprise() { calls.add("expressSurprise") }
        override fun waveHello() { calls.add("waveHello") }
        override fun dance() { calls.add("dance") }
        override fun think() { calls.add("think") }
        override fun resetToDefault() { calls.add("resetToDefault") }
    }

    private fun newRegistry(): Pair<RobotActionRegistry, RecordingExecutor> {
        val executor = RecordingExecutor()
        return RobotActionRegistry(executor) to executor
    }

    // ---------------- 注册与查找 ----------------

    @Test
    fun `注册表包含全部动作分类`() {
        val (registry, _) = newRegistry()
        val actions = registry.allActions()
        val categories = actions.map { it.category }.toSet()
        assertTrue(Category.HEAD in categories)
        assertTrue(Category.MOTION in categories)
        assertTrue(Category.EMOTION in categories)
        assertTrue(Category.COMBO in categories)
    }

    @Test
    fun `按名称查找已注册动作`() {
        val (registry, _) = newRegistry()
        assertNotNull(registry.findAction("self.robot.nod"))
        assertNotNull(registry.findAction("self.robot.move_forward"))
        assertNotNull(registry.findAction("self.robot.show_emotion"))
        assertNotNull(registry.findAction("self.robot.dance"))
    }

    @Test
    fun `查找未注册动作返回 null`() {
        val (registry, _) = newRegistry()
        assertNull(registry.findAction("self.robot.nonexistent"))
    }

    // ---------------- 执行 ----------------

    @Test
    fun `执行点头动作`() {
        val (registry, executor) = newRegistry()
        assertTrue(registry.execute("self.robot.nod", emptyMap()))
        assertTrue(executor.calls.any { it.startsWith("nodHead") })
    }

    @Test
    fun `执行移动动作带参数`() {
        val (registry, executor) = newRegistry()
        assertTrue(registry.execute("self.robot.move_forward", mapOf("speed" to 0.5, "duration" to 2000)))
        assertTrue(executor.calls.any { it.contains("moveForward") && it.contains("0.5") })
    }

    @Test
    fun `执行转向动作带参数`() {
        val (registry, executor) = newRegistry()
        assertTrue(registry.execute("self.robot.turn_left", mapOf("angle" to 45, "speed" to 20)))
        assertTrue(executor.calls.any { it.contains("turnLeft") })
    }

    @Test
    fun `执行表情动作`() {
        val (registry, executor) = newRegistry()
        assertTrue(registry.execute("self.robot.show_emotion", mapOf("name" to "happy", "speaking" to true)))
        assertTrue(executor.calls.any { it.contains("showEmotion") && it.contains("happy") })
    }

    @Test
    fun `执行组合动作`() {
        val (registry, executor) = newRegistry()
        assertTrue(registry.execute("self.robot.dance", emptyMap()))
        assertTrue(executor.calls.contains("dance"))
    }

    @Test
    fun `执行未知动作返回 false`() {
        val (registry, _) = newRegistry()
        assertFalse(registry.execute("self.robot.nonexistent", emptyMap()))
    }

    @Test
    fun `执行头部旋转缺少必填参数返回 false`() {
        val (registry, _) = newRegistry()
        assertFalse(registry.execute("self.robot.head_rotate", emptyMap()))
    }

    // ---------------- tools/list JSON ----------------

    @Test
    fun `tools list JSON 包含所有注册动作`() {
        val (registry, _) = newRegistry()
        val tools = registry.buildToolsListJson()
        assertEquals(registry.allActions().size, tools.size())
    }

    @Test
    fun `tools list JSON 中每个工具包含 name 与 description`() {
        val (registry, _) = newRegistry()
        val tools = registry.buildToolsListJson()
        for (i in 0 until tools.size()) {
            val tool = tools[i].asJsonObject
            assertTrue(tool.has("name"))
            assertTrue(tool.has("description"))
            assertTrue(tool.has("inputSchema"))
        }
    }

    @Test
    fun `tools list JSON 中头部旋转工具包含 angle 参数`() {
        val (registry, _) = newRegistry()
        val tools = registry.buildToolsListJson()
        val headRotate = (0 until tools.size())
            .map { tools[it].asJsonObject }
            .first { it.get("name").asString == "self.robot.head_rotate" }
        val props = headRotate.getAsJsonObject("inputSchema").getAsJsonObject("properties")
        assertTrue(props.has("angle"))
        assertTrue(props.has("speed"))
    }

    @Test
    fun `tools list JSON 中表情工具包含 enum 值`() {
        val (registry, _) = newRegistry()
        val tools = registry.buildToolsListJson()
        val emotion = (0 until tools.size())
            .map { tools[it].asJsonObject }
            .first { it.get("name").asString == "self.robot.show_emotion" }
        val props = emotion.getAsJsonObject("inputSchema").getAsJsonObject("properties")
        val nameParam = props.getAsJsonObject("name")
        assertTrue(nameParam.has("enum"))
        val enumValues = nameParam.getAsJsonArray("enum").map { it.asString }
        assertTrue("happy" in enumValues)
        assertTrue("excited" in enumValues)
    }
}
