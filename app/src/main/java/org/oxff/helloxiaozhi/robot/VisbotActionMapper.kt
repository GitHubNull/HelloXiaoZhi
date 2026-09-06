package org.oxff.helloxiaozhi.robot

import android.util.Log

/**
 * Visbot 对话动作映射器：根据 AI 回复内容自动触发相应的物理动作和表情。
 *
 * 通过关键词匹配实现，支持自定义规则扩展。
 */
class VisbotActionMapper(
    private val robotController: VisbotRobotController,
) {

    /** 是否启用动作映射（总开关） */
    var enabled: Boolean = true

    /**
     * 处理 AI 回复文本，自动触发相应动作
     * @param text AI 回复的文本内容
     * @param isAiSpeaking 当前 AI 是否正在说话（决定表情前缀）
     */
    fun processMessage(text: String, isAiSpeaking: Boolean = false) {
        if (!enabled || !robotController.isAvailable) return

        val lowerText = text.lowercase()
        Log.d(TAG, "Processing message: $text")

        // 按优先级匹配（先匹配更具体的规则）
        when {
            // 头部动作指令
            containsAny(lowerText, NOD_KEYWORDS) -> {
                Log.i(TAG, "Action: nod")
                robotController.nod()
            }
            containsAny(lowerText, SHAKE_HEAD_KEYWORDS) -> {
                Log.i(TAG, "Action: shake head")
                robotController.shakeHead()
            }
            containsAny(lowerText, LOOK_UP_KEYWORDS) -> {
                Log.i(TAG, "Action: look up")
                robotController.lookUp()
            }
            containsAny(lowerText, LOOK_DOWN_KEYWORDS) -> {
                Log.i(TAG, "Action: look down")
                robotController.lookDown()
            }
            containsAny(lowerText, CENTER_HEAD_KEYWORDS) -> {
                Log.i(TAG, "Action: center head")
                robotController.centerHead()
            }

            // 情感表达
            containsAny(lowerText, AGREEMENT_KEYWORDS) -> {
                Log.i(TAG, "Action: agreement")
                robotController.expressAgreement()
            }
            containsAny(lowerText, DISAGREEMENT_KEYWORDS) -> {
                Log.i(TAG, "Action: disagreement")
                robotController.expressDisagreement()
            }
            containsAny(lowerText, CURIOSITY_KEYWORDS) -> {
                Log.i(TAG, "Action: curiosity")
                robotController.expressCuriosity()
            }
            containsAny(lowerText, EXCITEMENT_KEYWORDS) -> {
                Log.i(TAG, "Action: excitement")
                robotController.expressExcitement()
            }
            containsAny(lowerText, LOVE_KEYWORDS) -> {
                Log.i(TAG, "Action: love")
                robotController.expressLove()
            }
            containsAny(lowerText, SHYNESS_KEYWORDS) -> {
                Log.i(TAG, "Action: shyness")
                robotController.expressShyness()
            }
            containsAny(lowerText, SURPRISE_KEYWORDS) -> {
                Log.i(TAG, "Action: surprise")
                robotController.expressSurprise()
            }
            containsAny(lowerText, BORED_KEYWORDS) -> {
                Log.i(TAG, "Action: bored")
                robotController.showEmotion("bored", speaking = isAiSpeaking)
            }
            containsAny(lowerText, CONFIDENCE_KEYWORDS) -> {
                Log.i(TAG, "Action: confidence")
                robotController.showEmotion("confidence", speaking = isAiSpeaking)
            }
            containsAny(lowerText, ENJOY_KEYWORDS) -> {
                Log.i(TAG, "Action: enjoy")
                robotController.showEmotion("enjoy", speaking = isAiSpeaking)
            }
            containsAny(lowerText, FUNNY_KEYWORDS) -> {
                Log.i(TAG, "Action: funny")
                robotController.showEmotion("funny", speaking = isAiSpeaking)
            }

            // 默认：根据说话状态显示默认表情
            else -> {
                Log.d(TAG, "Action: default emotion (speaking=$isAiSpeaking)")
                robotController.showEmotion("default", speaking = isAiSpeaking)
            }
        }
    }

    /**
     * 处理对话状态变更（由 ChatStateMachine 触发）
     * @param state 当前对话状态
     */
    fun onChatStateChanged(state: org.oxff.helloxiaozhi.chat.ChatState) {
        if (!enabled || !robotController.isAvailable) return

        when (state) {
            org.oxff.helloxiaozhi.chat.ChatState.AI_SPEAKING -> {
                // AI 说话时显示说话表情
                robotController.showEmotion("default", speaking = true)
            }
            org.oxff.helloxiaozhi.chat.ChatState.USER_SPEAKING -> {
                // 用户说话时显示倾听表情
                robotController.showEmotion("lookup", speaking = false)
            }
            org.oxff.helloxiaozhi.chat.ChatState.IDLE -> {
                // 空闲时显示默认表情
                robotController.showEmotion("default", speaking = false)
            }
        }
    }

    /** 检查文本是否包含任意关键词 */
    private fun containsAny(text: String, keywords: Array<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    companion object {
        private const val TAG = "VisbotActionMapper"

        // 头部动作关键词
        private val NOD_KEYWORDS = arrayOf("点头", "点点头", "nod")
        private val SHAKE_HEAD_KEYWORDS = arrayOf("摇头", "摇摇头", "shake head")
        private val LOOK_UP_KEYWORDS = arrayOf("抬头", "向上看", "look up")
        private val LOOK_DOWN_KEYWORDS = arrayOf("低头", "向下看", "look down")
        private val CENTER_HEAD_KEYWORDS = arrayOf("归中", "回正", "center")

        // 情感表达关键词
        private val AGREEMENT_KEYWORDS = arrayOf(
            "好的", "可以", "是的", "对", "没错", "同意", "行", "好", "ok", "yes", "sure"
        )
        private val DISAGREEMENT_KEYWORDS = arrayOf(
            "不行", "不可以", "不要", "不对", "拒绝", "不同意", "no", "don't", "can't"
        )
        private val CURIOSITY_KEYWORDS = arrayOf(
            "什么", "为什么", "怎么", "如何", "吗", "呢", "what", "why", "how", "?"
        )
        private val EXCITEMENT_KEYWORDS = arrayOf(
            "哈哈", "太好了", "真棒", "厉害", "哇塞", "棒", "great", "awesome", "amazing"
        )
        private val LOVE_KEYWORDS = arrayOf(
            "喜欢", "爱", "爱你", "喜欢你", "love", "like"
        )
        private val SHYNESS_KEYWORDS = arrayOf(
            "不好意思", "害羞", "惭愧", "抱歉", "shy", "sorry", "embarrassed"
        )
        private val SURPRISE_KEYWORDS = arrayOf(
            "哇", "真的吗", "不会吧", "天啊", "wow", "really", "surprise"
        )
        private val BORED_KEYWORDS = arrayOf(
            "无聊", "没意思", "厌倦", "bored", "boring"
        )
        private val CONFIDENCE_KEYWORDS = arrayOf(
            "自信", "相信自己", "加油", "confidence", "confident"
        )
        private val ENJOY_KEYWORDS = arrayOf(
            "享受", "开心", "快乐", "enjoy", "happy"
        )
        private val FUNNY_KEYWORDS = arrayOf(
            "搞笑", "有趣", "好玩", "funny", "fun", "interesting"
        )
    }
}
