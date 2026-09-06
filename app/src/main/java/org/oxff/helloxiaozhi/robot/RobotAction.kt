package org.oxff.helloxiaozhi.robot

/**
 * 动作参数定义（MCP Tool inputSchema 的客户端描述）。
 *
 * 用于向服务器端 AI 描述每个动作工具接受的参数类型、范围与默认值，
 * 使 AI 能根据对话语境生成合法的参数组合。
 */
data class ActionParam(
    val name: String,
    val type: String,        // "integer" | "number" | "string" | "boolean"
    val description: String,
    val required: Boolean = false,
    val minimum: Number? = null,
    val maximum: Number? = null,
    val default: Any? = null,
    val enumValues: List<String>? = null,
)

/**
 * 动作定义（MCP Tool 的客户端描述）。
 *
 * 每个动作对应一个可通过 MCP tools/call 调用的机器人行为，
 * 包含元数据（名称/描述/参数）与执行器（实际调用硬件控制器的 lambda）。
 */
data class RobotAction(
    /** MCP 工具名，如 "self.robot.nod" */
    val name: String,
    /** 给 AI 看的自然语言描述 */
    val description: String,
    /** 动作分类 */
    val category: Category,
    /** 参数列表 */
    val params: List<ActionParam> = emptyList(),
    /** 执行器：接收参数 map，返回是否成功 */
    val executor: (Map<String, Any?>) -> Boolean,
)

/** 动作分类 */
enum class Category { HEAD, MOTION, EMOTION, COMBO }
