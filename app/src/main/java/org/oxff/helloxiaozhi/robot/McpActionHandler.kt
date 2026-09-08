package org.oxff.helloxiaozhi.robot

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * MCP（Model Context Protocol）动作处理器。
 *
 * 处理服务器通过 WebSocket 下发的 JSON-RPC 2.0 格式 MCP 消息，
 * 支持 initialize、tools/list、tools/call 三种方法。
 *
 * 协议参考：xiaozhi-esp32 的 MCP 实现（main/mcp_server.cc）。
 *
 * 消息封装格式：
 * ```json
 * {
 *   "session_id": "xxx",
 *   "type": "mcp",
 *   "payload": { "jsonrpc": "2.0", "method": "...", "params": {...}, "id": ... }
 * }
 * ```
 */
class McpActionHandler(
    private val registry: RobotActionRegistry,
) {

    /** 是否启用 MCP 动作处理（总开关） */
    var enabled: Boolean = true

    /** 日志钩子（生产环境注入 android.util.Log，单元测试默认 no-op） */
    var logInfo: (String) -> Unit = {}
    var logWarn: (String) -> Unit = {}
    var logDebug: (String) -> Unit = {}

    /**
     * 处理 MCP 消息，返回需要回发给服务器的响应 JSON（不需要响应时返回 null）。
     *
     * @param payload MCP 消息的 payload 字段（JSON-RPC 2.0 对象）
     * @return 响应 JSON（包含 id 的 JSON-RPC 响应），或 null（通知类消息无需响应）
     */
    fun handleMcpMessage(payload: JsonObject): JsonObject? {
        if (!enabled) return null

        val method = payload.get("method")?.asString ?: run {
            logWarn("MCP message missing method")
            return buildErrorResponse(payload, -32600, "Missing method")
        }
        val id = payload.get("id")

        logInfo("MCP method=$method, id=$id")

        return when (method) {
            "initialize" -> handleInitialize(id)
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(id, payload.getAsJsonObject("params"))
            else -> {
                // 通知类消息（无 id）不需要响应
                if (id == null) {
                    logDebug("MCP notification: $method")
                    null
                } else {
                    buildErrorResponse(payload, -32601, "Unknown method: $method")
                }
            }
        }
    }

    // ---------------- initialize ----------------

    /**
     * 处理 initialize 请求：返回协议版本与设备能力声明。
     */
    private fun handleInitialize(id: com.google.gson.JsonElement?): JsonObject {
        val result = JsonObject()
        result.addProperty("protocolVersion", PROTOCOL_VERSION)

        val capabilities = JsonObject()
        capabilities.add("tools", JsonObject())
        result.add("capabilities", capabilities)

        val serverInfo = JsonObject()
        serverInfo.addProperty("name", "HelloXiaoZhi-Android")
        serverInfo.addProperty("version", "1.0.0")
        result.add("serverInfo", serverInfo)

        return buildSuccessResponse(id, result)
    }

    // ---------------- tools/list ----------------

    /**
     * 处理 tools/list 请求：返回全部已注册的动作工具列表。
     *
     * 不返回 nextCursor：本端工具数量少，一次返回全部。
     * 若返回空字符串 cursor，服务器会误判为“分页未结束”而无限重复拉取。
     */
    private fun handleToolsList(id: com.google.gson.JsonElement?): JsonObject {
        val result = JsonObject()
        result.add("tools", registry.buildToolsListJson())
        return buildSuccessResponse(id, result)
    }

    // ---------------- tools/call ----------------

    /**
     * 处理 tools/call 请求：查找动作并执行，返回执行结果。
     */
    private fun handleToolsCall(id: com.google.gson.JsonElement?, params: JsonObject?): JsonObject {
        if (params == null) {
            return buildErrorResponse(id, -32602, "Missing params")
        }

        val toolName = params.get("name")?.asString ?: run {
            return buildErrorResponse(id, -32602, "Missing tool name")
        }

        val arguments = params.getAsJsonObject("arguments")
        val argsMap = mutableMapOf<String, Any?>()
        arguments?.entrySet()?.forEach { (key, value) ->
            argsMap[key] = when {
                value.isJsonPrimitive -> {
                    val prim = value.asJsonPrimitive
                    when {
                        prim.isNumber -> prim.asNumber
                        prim.isBoolean -> prim.asBoolean
                        else -> prim.asString
                    }
                }
                else -> value.toString()
            }
        }

        logInfo("Executing tool: $toolName, args: $argsMap")

        val resultJson = registry.execute(toolName, argsMap)

        return if (resultJson != null) {
            val result = JsonObject()
            val content = JsonArray()
            val textContent = JsonObject()
            textContent.addProperty("type", "text")
            textContent.addProperty("text", resultJson)
            content.add(textContent)
            result.add("content", content)
            result.addProperty("isError", false)
            buildSuccessResponse(id, result)
        } else {
            buildErrorResponse(id, -32601, "Tool execution failed: $toolName")
        }
    }

    // ---------------- 响应构建 ----------------

    private fun buildSuccessResponse(id: com.google.gson.JsonElement?, result: JsonObject): JsonObject {
        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        if (id != null) response.add("id", id)
        response.add("result", result)
        return response
    }

    private fun buildErrorResponse(id: com.google.gson.JsonElement?, code: Int, message: String): JsonObject {
        val response = JsonObject()
        response.addProperty("jsonrpc", "2.0")
        if (id != null) response.add("id", id)
        val error = JsonObject()
        error.addProperty("code", code)
        error.addProperty("message", message)
        response.add("error", error)
        return response
    }

    private fun buildErrorResponse(request: JsonObject, code: Int, message: String): JsonObject {
        return buildErrorResponse(request.get("id"), code, message)
    }

    companion object {
        private const val PROTOCOL_VERSION = "2024-11-05"
    }
}
