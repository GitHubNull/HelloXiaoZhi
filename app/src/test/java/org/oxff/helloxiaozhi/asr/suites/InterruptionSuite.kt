package org.oxff.helloxiaozhi.asr.suites

import org.oxff.helloxiaozhi.asr.AsrTestScenario
import org.oxff.helloxiaozhi.asr.Trigger
import org.oxff.helloxiaozhi.asr.scenario
import org.oxff.helloxiaozhi.chat.ChatState

/**
 * 打断场景测试套件：验证用户打断 AI 说话时的识别能力。
 *
 * 对齐 ChatStateMachine 打断逻辑：AI_SPEAKING 期间电平 > 0.1 连续 3 帧
 * → 发送 abort → USER_SPEAKING。场景携带 "interrupt" 标签，
 * 执行器会在喂帧前将状态机置为 AI_SPEAKING 模拟 AI 正在说话。
 *
 * 注意：打断阈值为 0.1（高于说话阈值 0.02），打断语音必须足够响亮；
 * 句首轻声（<0.1）无法打断属于设计行为，不作为缺陷。
 */
object InterruptionSuite {

    val scenarios: List<AsrTestScenario> = listOf(
        // 场景 1：AI 说话中途打断（正常音量）
        scenario("打断_AI说话中途_正常音量") {
            description("AI 说话中用户以正常音量打断，应发 abort 并重建音频流")
            chineseSpeech("等一下我有个问题", initialSoftness = 1.0f)
            expectStt("等一下我有个问题")
            expectTransition(ChatState.AI_SPEAKING, ChatState.USER_SPEAKING,
                Trigger.USER_INTERRUPT, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("interrupt")
        },

        // 场景 2：AI 说话开头打断
        scenario("打断_AI刚开口_立即打断") {
            description("AI 刚开始说话即被打断，验证快速响应")
            chineseSpeech("不用说了我知道", initialSoftness = 1.0f)
            expectStt("不用说了我知道")
            expectTransition(ChatState.AI_SPEAKING, ChatState.USER_SPEAKING,
                Trigger.USER_INTERRUPT, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("interrupt")
        },

        // 场景 3：打断后句子较长（验证打断后持续上行）
        scenario("打断_打断后长句") {
            description("打断后继续说长句，验证打断后音频流持续完整上行")
            chineseSpeech("你先别说了我想问的是明天还能不能继续", initialSoftness = 1.0f)
            expectStt("你先别说了我想问的是明天还能不能继续")
            expectTransition(ChatState.AI_SPEAKING, ChatState.USER_SPEAKING,
                Trigger.USER_INTERRUPT, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("interrupt")
        },

        // 场景 4：AI 说话中低音量轻声（不应打断，设计行为）
        scenario("打断_低音量_不应打断") {
            description("电平低于打断阈值 0.1 的轻声不应触发打断")
            chineseSpeech("嗯", initialSoftness = 0.2f)
            expectStt("")
            withTag("interrupt")
            withTag("negative")
        }
    )
}
