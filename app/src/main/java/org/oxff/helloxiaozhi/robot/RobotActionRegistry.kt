package org.oxff.helloxiaozhi.robot

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.oxff.helloxiaozhi.music.MusicLibrary
import org.oxff.helloxiaozhi.music.MusicPlayer

/**
 * 动作注册表：集中管理所有可用的机器人动作工具。
 *
 * 职责：
 *  - 注册全部动作（头部/移动/表情/组合/音乐）
 *  - 生成 MCP tools/list 响应（JSON-RPC 格式）
 *  - 按名称查找动作并执行（tools/call 分发）
 *
 * 动作命名规范：self.robot.<action>，与 xiaozhi-esp32 的
 * self.audio_speaker.*、self.light.* 等命名空间保持一致。
 * 音乐动作为 self.music.<action>。
 */
class RobotActionRegistry(
    private val executor: RobotActionExecutor,
    private val musicPlayer: MusicPlayer? = null,
    private val musicLibrary: MusicLibrary? = null,
) {

    /** 日志钩子（生产环境注入 android.util.Log，单元测试默认 no-op） */
    var logWarn: (String) -> Unit = {}
    var logError: (String, Throwable) -> Unit = { _, _ -> }

    /** 已注册的动作列表（按注册顺序） */
    private val actions = mutableListOf<RobotAction>()

    /** 动作名称 → 动作 索引（快速查找） */
    private val actionMap = mutableMapOf<String, RobotAction>()

    init {
        registerAll()
    }

    /** 注册全部动作 */
    private fun registerAll() {
        registerHeadActions()
        registerMotionActions()
        registerEmotionActions()
        registerComboActions()
        registerMusicActions()
    }

    // ---------------- 注册 ----------------

    private fun register(action: RobotAction) {
        actions.add(action)
        actionMap[action.name] = action
    }

    // ---------------- 头部动作 ----------------

    private fun registerHeadActions() {
        register(RobotAction(
            name = "self.robot.nod",
            description = "点头，表达肯定、同意或打招呼",
            category = Category.HEAD,
            params = listOf(
                ActionParam("speed", "integer", "旋转速度 0-100", default = 50, minimum = 0, maximum = 100),
            ),
            executor = { params ->
                val speed = (params["speed"] as? Number)?.toInt() ?: 50
                executor.nodHead(speed)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.shake_head",
            description = "摇头，表达否定、拒绝或不同意",
            category = Category.HEAD,
            params = listOf(
                ActionParam("speed", "integer", "旋转速度 0-100", default = 50, minimum = 0, maximum = 100),
            ),
            executor = { params ->
                val speed = (params["speed"] as? Number)?.toInt() ?: 50
                executor.shakeHead(speed)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.look_up",
            description = "抬头向上看",
            category = Category.HEAD,
            params = listOf(
                ActionParam("speed", "integer", "旋转速度 0-100", default = 50, minimum = 0, maximum = 100),
            ),
            executor = { params ->
                val speed = (params["speed"] as? Number)?.toInt() ?: 50
                executor.rotateHead(20f, speed)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.look_down",
            description = "低头向下看",
            category = Category.HEAD,
            params = listOf(
                ActionParam("speed", "integer", "旋转速度 0-100", default = 50, minimum = 0, maximum = 100),
            ),
            executor = { params ->
                val speed = (params["speed"] as? Number)?.toInt() ?: 50
                executor.rotateHead(-20f, speed)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.look_left",
            description = "向左看",
            category = Category.HEAD,
            params = listOf(
                ActionParam("speed", "integer", "旋转速度 0-100", default = 50, minimum = 0, maximum = 100),
            ),
            executor = { params ->
                val speed = (params["speed"] as? Number)?.toInt() ?: 50
                executor.rotateHead(-20f, speed)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.look_right",
            description = "向右看",
            category = Category.HEAD,
            params = listOf(
                ActionParam("speed", "integer", "旋转速度 0-100", default = 50, minimum = 0, maximum = 100),
            ),
            executor = { params ->
                val speed = (params["speed"] as? Number)?.toInt() ?: 50
                executor.rotateHead(20f, speed)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.center_head",
            description = "头部归中回正",
            category = Category.HEAD,
            params = listOf(
                ActionParam("speed", "integer", "旋转速度 0-100", default = 50, minimum = 0, maximum = 100),
            ),
            executor = { params ->
                val speed = (params["speed"] as? Number)?.toInt() ?: 50
                executor.rotateHead(0f, speed)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.head_rotate",
            description = "头部旋转到指定角度",
            category = Category.HEAD,
            params = listOf(
                ActionParam("angle", "number", "目标角度（-23 到 25）", required = true, minimum = -23, maximum = 25),
                ActionParam("speed", "integer", "旋转速度 0-100", default = 50, minimum = 0, maximum = 100),
            ),
            executor = { params ->
                val angle = (params["angle"] as? Number)?.toFloat()
                    ?: return@RobotAction false
                val speed = (params["speed"] as? Number)?.toInt() ?: 50
                executor.rotateHead(angle, speed)
                true
            },
        ))
    }

    // ---------------- 移动动作 ----------------

    private fun registerMotionActions() {
        register(RobotAction(
            name = "self.robot.move_forward",
            description = "向前移动",
            category = Category.MOTION,
            params = listOf(
                ActionParam("speed", "number", "移动速度 0-1", default = 0.3, minimum = 0, maximum = 1),
                ActionParam("duration", "integer", "持续时间（毫秒）", default = 1000, minimum = 100, maximum = 10000),
            ),
            executor = { params ->
                val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f
                val duration = (params["duration"] as? Number)?.toLong() ?: 1000L
                executor.moveForward(speed, duration)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.move_backward",
            description = "向后移动",
            category = Category.MOTION,
            params = listOf(
                ActionParam("speed", "number", "移动速度 0-1", default = 0.3, minimum = 0, maximum = 1),
                ActionParam("duration", "integer", "持续时间（毫秒）", default = 1000, minimum = 100, maximum = 10000),
            ),
            executor = { params ->
                val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f
                val duration = (params["duration"] as? Number)?.toLong() ?: 1000L
                executor.moveBackward(speed, duration)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.turn_left",
            description = "向左转",
            category = Category.MOTION,
            params = listOf(
                ActionParam("angle", "number", "转向角度 0-360", default = 90, minimum = 0, maximum = 360),
                ActionParam("speed", "number", "转向速度（度/秒）0-90", default = 30, minimum = 0, maximum = 90),
            ),
            executor = { params ->
                val angle = (params["angle"] as? Number)?.toFloat() ?: 90f
                val speed = (params["speed"] as? Number)?.toFloat() ?: 30f
                executor.turnLeft(speed, angle)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.turn_right",
            description = "向右转",
            category = Category.MOTION,
            params = listOf(
                ActionParam("angle", "number", "转向角度 0-360", default = 90, minimum = 0, maximum = 360),
                ActionParam("speed", "number", "转向速度（度/秒）0-90", default = 30, minimum = 0, maximum = 90),
            ),
            executor = { params ->
                val angle = (params["angle"] as? Number)?.toFloat() ?: 90f
                val speed = (params["speed"] as? Number)?.toFloat() ?: 30f
                executor.turnRight(speed, angle)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.stop_moving",
            description = "停止移动",
            category = Category.MOTION,
            executor = {
                executor.stopMoving()
                true
            },
        ))
    }

    // ---------------- 表情动作 ----------------

    private fun registerEmotionActions() {
        val emotionNames = listOf(
            "happy", "excited", "surprised", "love", "shy", "curious",
            "alert", "confidence", "enjoy", "funny", "bored", "default",
            "lookup", "lookdown", "lookleft", "lookright",
        )

        register(RobotAction(
            name = "self.robot.show_emotion",
            description = "显示表情动画",
            category = Category.EMOTION,
            params = listOf(
                ActionParam("name", "string", "表情名称", required = true, enumValues = emotionNames),
                ActionParam("speaking", "boolean", "是否正在说话", default = false),
                ActionParam("loops", "integer", "循环次数", default = 1, minimum = 1, maximum = 10),
            ),
            executor = { params ->
                val name = params["name"] as? String ?: return@RobotAction false
                val speaking = params["speaking"] as? Boolean ?: false
                val loops = (params["loops"] as? Number)?.toInt() ?: 1
                executor.showEmotion(name, speaking, loops)
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.dismiss_emotion",
            description = "消除当前表情",
            category = Category.EMOTION,
            executor = {
                executor.dismissEmotion()
                true
            },
        ))
    }

    // ---------------- 组合动作 ----------------

    private fun registerComboActions() {
        register(RobotAction(
            name = "self.robot.express_agreement",
            description = "表达肯定：点头 + 开心表情",
            category = Category.COMBO,
            executor = {
                executor.expressAgreement()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.express_disagreement",
            description = "表达否定：摇头 + 警觉表情",
            category = Category.COMBO,
            executor = {
                executor.expressDisagreement()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.express_curiosity",
            description = "表达好奇：歪头 + 好奇表情",
            category = Category.COMBO,
            executor = {
                executor.expressCuriosity()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.express_excitement",
            description = "表达兴奋：点头 + 兴奋表情",
            category = Category.COMBO,
            executor = {
                executor.expressExcitement()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.express_love",
            description = "表达爱意：爱心表情 + 轻点头",
            category = Category.COMBO,
            executor = {
                executor.expressLove()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.express_shyness",
            description = "表达害羞：害羞表情 + 低头",
            category = Category.COMBO,
            executor = {
                executor.expressShyness()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.express_surprise",
            description = "表达惊讶：惊讶表情 + 抬头",
            category = Category.COMBO,
            executor = {
                executor.expressSurprise()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.wave_hello",
            description = "打招呼：右转 + 开心表情 + 回正",
            category = Category.COMBO,
            executor = {
                executor.waveHello()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.dance",
            description = "庆祝舞蹈：左右转 + 兴奋表情",
            category = Category.COMBO,
            executor = {
                executor.dance()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.think",
            description = "思考姿态：低头 + 好奇表情",
            category = Category.COMBO,
            executor = {
                executor.think()
                true
            },
        ))

        register(RobotAction(
            name = "self.robot.reset",
            description = "重置到默认状态：归中 + 停止移动 + 默认表情",
            category = Category.COMBO,
            executor = {
                executor.resetToDefault()
                true
            },
        ))
    }

    // ---------------- 音乐动作 ----------------

    private fun registerMusicActions() {
        val player = musicPlayer ?: return
        val library = musicLibrary ?: return

        register(RobotAction(
            name = "self.music.play",
            description = "播放音乐。可通过 track 指定曲名或歌手，通过 genre 指定类型，或设置 random 为 true 随机播放",
            category = Category.MUSIC,
            params = listOf(
                ActionParam("track", "string", "曲名或歌手关键词"),
                ActionParam("genre", "string", "音乐类型（如：轻音乐、摇滚、流行、古典、爵士）"),
                ActionParam("random", "boolean", "是否随机播放", default = false),
            ),
            executor = { params ->
                val trackKeyword = params["track"] as? String
                val genre = params["genre"] as? String
                val random = params["random"] as? Boolean ?: false

                val track = when {
                    !trackKeyword.isNullOrEmpty() -> {
                        // 按关键词搜索（标题或歌手）
                        library.search(trackKeyword).firstOrNull()
                    }
                    !genre.isNullOrEmpty() -> {
                        // 按类型随机
                        library.randomByGenre(genre)
                    }
                    random -> {
                        // 随机播放
                        library.randomTrack()
                    }
                    else -> null
                }

                if (track != null) {
                    player.play(track)
                    true
                } else {
                    logWarn("No track found for play: track=$trackKeyword, genre=$genre, random=$random")
                    false
                }
            },
        ))

        register(RobotAction(
            name = "self.music.pause",
            description = "暂停音乐播放",
            category = Category.MUSIC,
            executor = {
                player.pause()
                true
            },
        ))

        register(RobotAction(
            name = "self.music.resume",
            description = "恢复音乐播放",
            category = Category.MUSIC,
            executor = {
                player.resume()
                true
            },
        ))

        register(RobotAction(
            name = "self.music.stop",
            description = "停止音乐播放",
            category = Category.MUSIC,
            executor = {
                player.stop()
                true
            },
        ))

        register(RobotAction(
            name = "self.music.next",
            description = "播放下一首",
            category = Category.MUSIC,
            executor = {
                player.next()
            },
        ))

        register(RobotAction(
            name = "self.music.previous",
            description = "播放上一首",
            category = Category.MUSIC,
            executor = {
                player.previous()
            },
        ))

        register(RobotAction(
            name = "self.music.list",
            description = "列出当前音乐库中的所有曲目",
            category = Category.MUSIC,
            executor = {
                // 返回曲目列表信息（通过日志输出，实际结果由 MCP 响应携带）
                val tracks = library.allTracks()
                logWarn("Music library: ${tracks.size} tracks")
                true
            },
        ))
    }

    // ---------------- 查询与执行 ----------------

    /** 按名称查找动作 */
    fun findAction(name: String): RobotAction? = actionMap[name]

    /** 获取所有已注册动作 */
    fun allActions(): List<RobotAction> = actions.toList()

    /**
     * 执行指定动作
     * @param name 动作名称
     * @param arguments 参数 map
     * @return 是否成功执行
     */
    fun execute(name: String, arguments: Map<String, Any?>): Boolean {
        val action = actionMap[name] ?: run {
            logWarn("Unknown action: $name")
            return false
        }
        return try {
            action.executor(arguments)
        } catch (e: Exception) {
            logError("Failed to execute action: $name", e)
            false
        }
    }

    /**
     * 生成 MCP tools/list 响应的 tools 数组
     */
    fun buildToolsListJson(): JsonArray {
        val tools = JsonArray()
        for (action in actions) {
            tools.add(buildToolJson(action))
        }
        return tools
    }

    /** 构建单个工具的 JSON 描述 */
    private fun buildToolJson(action: RobotAction): JsonObject {
        val tool = JsonObject()
        tool.addProperty("name", action.name)
        tool.addProperty("description", action.description)

        val schema = JsonObject()
        schema.addProperty("type", "object")

        if (action.params.isNotEmpty()) {
            val properties = JsonObject()
            val required = JsonArray()

            for (param in action.params) {
                val prop = JsonObject()
                prop.addProperty("type", param.type)
                prop.addProperty("description", param.description)
                param.minimum?.let { prop.addProperty("minimum", it) }
                param.maximum?.let { prop.addProperty("maximum", it) }
                param.default?.let { default ->
                    when (default) {
                        is Number -> prop.addProperty("default", default)
                        is Boolean -> prop.addProperty("default", default)
                        is String -> prop.addProperty("default", default)
                        else -> prop.addProperty("default", default.toString())
                    }
                }
                param.enumValues?.let { values ->
                    val enumArray = JsonArray()
                    values.forEach { enumArray.add(it) }
                    prop.add("enum", enumArray)
                }
                properties.add(param.name, prop)

                if (param.required) {
                    required.add(param.name)
                }
            }

            schema.add("properties", properties)
            if (required.size() > 0) {
                schema.add("required", required)
            }
        }

        tool.add("inputSchema", schema)
        return tool
    }

    companion object {
    }
}
