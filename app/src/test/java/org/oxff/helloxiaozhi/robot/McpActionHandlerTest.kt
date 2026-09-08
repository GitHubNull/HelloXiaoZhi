package org.oxff.helloxiaozhi.robot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpActionHandler 单元测试：MCP 协议消息处理（initialize / tools/list / tools/call）。
 */
class McpActionHandlerTest {

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

    private fun newHandler(): Pair<McpActionHandler, RecordingExecutor> {
        val executor = RecordingExecutor()
        val registry = RobotActionRegistry(executor)
        return McpActionHandler(registry) to executor
    }

    private fun buildPayload(method: String, id: Int? = null, params: JsonObject? = null): JsonObject {
        val payload = JsonObject()
        payload.addProperty("jsonrpc", "2.0")
        payload.addProperty("method", method)
        id?.let { payload.addProperty("id", it) }
        params?.let { payload.add("params", it) }
        return payload
    }

    // ---------------- initialize ----------------

    @Test
    fun `initialize 返回协议版本与能力声明`() {
        val (handler, _) = newHandler()
        val response = handler.handleMcpMessage(buildPayload("initialize", id = 1))

        assertNotNull(response)
        assertEquals("2.0", response!!.get("jsonrpc").asString)
        assertEquals(1, response.get("id").asInt)

        val result = response.getAsJsonObject("result")
        assertEquals("2024-11-05", result.get("protocolVersion").asString)
        assertTrue(result.getAsJsonObject("capabilities").has("tools"))
        assertEquals("HelloXiaoZhi-Android", result.getAsJsonObject("serverInfo").get("name").asString)
    }

    // ---------------- tools/list ----------------

    @Test
    fun `tools list 返回全部注册工具`() {
        val (handler, _) = newHandler()
        val response = handler.handleMcpMessage(buildPayload("tools/list", id = 2))

        assertNotNull(response)
        assertEquals(2, response!!.get("id").asInt)

        val result = response.getAsJsonObject("result")
        val tools = result.getAsJsonArray("tools")
        assertTrue(tools.size() > 0)

        // 验证包含核心工具
        val names = (0 until tools.size()).map { tools[it].asJsonObject.get("name").asString }
        assertTrue("self.robot.nod" in names)
        assertTrue("self.robot.move_forward" in names)
        assertTrue("self.robot.show_emotion" in names)
        assertTrue("self.robot.dance" in names)
    }

    @Test
    fun `tools list 中每个工具包含 inputSchema`() {
        val (handler, _) = newHandler()
        val response = handler.handleMcpMessage(buildPayload("tools/list", id = 2))
        val tools = response!!.getAsJsonObject("result").getAsJsonArray("tools")

        for (i in 0 until tools.size()) {
            val tool = tools[i].asJsonObject
            assertTrue("Tool ${tool.get("name")} missing inputSchema", tool.has("inputSchema"))
        }
    }

    // ---------------- tools/call ----------------

    @Test
    fun `tools call 执行点头动作`() {
        val (handler, executor) = newHandler()
        val params = JsonObject()
        params.addProperty("name", "self.robot.nod")
        val response = handler.handleMcpMessage(buildPayload("tools/call", id = 3, params = params))

        assertNotNull(response)
        assertEquals(3, response!!.get("id").asInt)

        val result = response.getAsJsonObject("result")
        assertEquals(false, result.get("isError").asBoolean)
        assertTrue(executor.calls.any { it.startsWith("nodHead") })
    }

    @Test
    fun `tools call 执行移动动作带参数`() {
        val (handler, executor) = newHandler()
        val params = JsonObject()
        params.addProperty("name", "self.robot.move_forward")
        val args = JsonObject()
        args.addProperty("speed", 0.5)
        args.addProperty("duration", 2000)
        params.add("arguments", args)

        val response = handler.handleMcpMessage(buildPayload("tools/call", id = 4, params = params))

        assertNotNull(response)
        assertEquals(false, response!!.getAsJsonObject("result").get("isError").asBoolean)
        assertTrue(executor.calls.any { it.contains("moveForward") })
    }

    @Test
    fun `tools call 返回结构化 text content`() {
        val (handler, _) = newHandler()
        val params = JsonObject()
        params.addProperty("name", "self.robot.nod")
        val response = handler.handleMcpMessage(buildPayload("tools/call", id = 9, params = params))

        assertNotNull(response)
        val result = response!!.getAsJsonObject("result")
        val content = result.getAsJsonArray("content")
        assertTrue(content.size() > 0)
        val text = content[0].asJsonObject.get("text").asString
        // 动作类工具成功时返回统一 JSON
        val parsed = JsonParser.parseString(text).asJsonObject
        assertEquals("ok", parsed.get("result").asString)
    }

    @Test
    fun `tools call 未知工具返回错误`() {
        val (handler, _) = newHandler()
        val params = JsonObject()
        params.addProperty("name", "self.robot.nonexistent")
        val response = handler.handleMcpMessage(buildPayload("tools/call", id = 5, params = params))

        assertNotNull(response)
        val error = response!!.getAsJsonObject("error")
        assertNotNull(error)
        assertEquals(-32601, error.get("code").asInt)
        assertTrue(error.get("message").asString.contains("Tool execution failed"))
    }

    @Test
    fun `tools call 缺少工具名返回错误`() {
        val (handler, _) = newHandler()
        val params = JsonObject()
        val response = handler.handleMcpMessage(buildPayload("tools/call", id = 6, params = params))

        assertNotNull(response)
        val error = response!!.getAsJsonObject("error")
        assertNotNull(error)
        assertEquals(-32602, error.get("code").asInt)
    }

    @Test
    fun `tools call 缺少 params 返回错误`() {
        val (handler, _) = newHandler()
        val response = handler.handleMcpMessage(buildPayload("tools/call", id = 7))

        assertNotNull(response)
        val error = response!!.getAsJsonObject("error")
        assertNotNull(error)
        assertEquals(-32602, error.get("code").asInt)
    }

    // ---------------- 未知方法 ----------------

    @Test
    fun `未知方法带 id 返回错误`() {
        val (handler, _) = newHandler()
        val response = handler.handleMcpMessage(buildPayload("unknown/method", id = 8))

        assertNotNull(response)
        val error = response!!.getAsJsonObject("error")
        assertEquals(-32601, error.get("code").asInt)
    }

    @Test
    fun `通知类消息无 id 返回 null`() {
        val (handler, _) = newHandler()
        val response = handler.handleMcpMessage(buildPayload("notifications/state_changed"))
        assertNull(response)
    }

    // ---------------- 开关 ----------------

    @Test
    fun `禁用时不处理任何消息`() {
        val (handler, _) = newHandler()
        handler.enabled = false
        val response = handler.handleMcpMessage(buildPayload("initialize", id = 1))
        assertNull(response)
    }

    // ---------------- 完整消息解析 ----------------

    @Test
    fun `解析完整 MCP 消息 JSON`() {
        val (handler, executor) = newHandler()
        val json = """{"jsonrpc":"2.0","method":"tools/call","id":10,"params":{"name":"self.robot.dance","arguments":{}}}"""
        val payload = JsonParser.parseString(json).asJsonObject
        val response = handler.handleMcpMessage(payload)

        assertNotNull(response)
        assertEquals(10, response!!.get("id").asInt)
        assertTrue(executor.calls.contains("dance"))
    }
}
