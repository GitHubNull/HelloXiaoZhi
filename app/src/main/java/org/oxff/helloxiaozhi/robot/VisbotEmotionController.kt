package org.oxff.helloxiaozhi.robot

import android.net.Uri
import android.util.Log
import com.ubtrobot.Robot
import com.ubtrobot.emotion.EmotionController
import com.ubtrobot.emotion.ExpressingOption

/**
 * Visbot 表情控制器：控制屏幕表情动画（SVGA 格式）。
 *
 * 表情资源位于 /system/media/visbot/emotion/，分为：
 *  - shuohua/：说话时的表情（speak_ 前缀）
 *  - bushuohua/：不说话时的表情（silent_ 前缀）
 *
 * 每种表情有 1-4 个变体，随机选择以增加生动性。
 */
class VisbotEmotionController {

    /** 表情服务是否可用 */
    val isAvailable: Boolean
        get() = emotionController != null

    private var emotionController: EmotionController? = null

    init {
        try {
            emotionController = Robot.globalContext().getSystemService("emotion")
            Log.i(TAG, "EmotionController initialized: ${emotionController != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get EmotionController", e)
        }
    }

    /**
     * 显示表情
     * @param name 表情名称（happy, excited, surprised, love, shy, curious, alert, confidence, enjoy, funny, default, lookup, lookdown, lookleft, lookright）
     * @param speaking 是否正在说话（决定使用 speak_ 还是 silent_ 前缀）
     * @param variant 变体编号（1-4，默认随机）
     * @param loops 循环次数（默认 1 次）
     */
    fun showEmotion(
        name: String,
        speaking: Boolean = false,
        variant: Int = (1..4).random(),
        loops: Int = 1,
    ) {
        val controller = emotionController ?: run {
            Log.w(TAG, "EmotionController not available")
            return
        }

        val mode = if (speaking) MODE_SPEAKING else MODE_SILENT
        val prefix = if (speaking) PREFIX_SPEAK else PREFIX_SILENT
        val safeVariant = variant.coerceIn(1, 4)
        val fileName = "${prefix}${name}_${safeVariant}.svga"
        val uri = Uri.parse("file:///system/media/visbot/emotion/$mode/$fileName")

        try {
            Log.i(TAG, "Creating ExpressingOption with uri: $uri")
            val option = ExpressingOption.Builder(uri)
                .setLoops(loops)
                .setSticky(false)
                .build()
            Log.i(TAG, "Calling express with option: $option")
            val promise = controller.express(option)
            Log.i(TAG, "Express promise created: ${promise != null}, emotion: $name (mode=$mode, variant=$safeVariant, loops=$loops)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show emotion: $name", e)
        }
    }

    /** 消除当前表情 */
    fun dismiss() {
        val controller = emotionController ?: return
        try {
            controller.dismiss()
            Log.i(TAG, "Emotion dismissed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss emotion", e)
        }
    }

    /** 是否正在显示表情 */
    fun isExpressing(): Boolean {
        return try {
            emotionController?.isExpressing ?: false
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "VisbotEmotionController"

        /** 说话时的表情目录 */
        private const val MODE_SPEAKING = "shuohua"

        /** 不说话时的表情目录 */
        private const val MODE_SILENT = "bushuohua"

        /** 说话时的表情前缀 */
        private const val PREFIX_SPEAK = "speak_"

        /** 不说话时的表情前缀 */
        private const val PREFIX_SILENT = "silent_"
    }
}
