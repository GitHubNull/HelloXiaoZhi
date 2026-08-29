package org.oxff.helloxiaozhi.asr.suites

import org.oxff.helloxiaozhi.asr.AsrTestScenario
import org.oxff.helloxiaozhi.asr.Trigger
import org.oxff.helloxiaozhi.asr.scenario
import org.oxff.helloxiaozhi.chat.ChatState

/**
 * 基础功能测试套件：验证基本语音交互流程。
 * 覆盖：正常语速中文句子、句首轻声句子、短句、长句。
 */
object BasicFunctionalitySuite {

    val scenarios: List<AsrTestScenario> = listOf(
        // 场景 1：正常语速中文句子
        scenario("基础_正常语速_今天天气怎么样") {
            description("正常语速（约 5 字/秒）的标准普通话问句")
            chineseSpeech("今天天气怎么样", initialSoftness = 1.0f)
            expectStt("今天天气怎么样")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("basic")
        },

        // 场景 2：句首轻声句子
        scenario("基础_句首轻声_你好小智") {
            description("句首字「你」为轻声（电平约为正常字的 40%）")
            chineseSpeech("你好小智", initialSoftness = 0.4f)
            expectStt("你好小智")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("basic")
            withTag("softness")
        },

        // 场景 3：短句（双字）
        scenario("基础_短句_你好") {
            description("最短交互语句，验证极短语音的完整链路")
            chineseSpeech("你好", initialSoftness = 0.8f)
            expectStt("你好")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("basic")
        },

        // 场景 4：长句（>10 秒）
        scenario("基础_长句_超过十秒的完整陈述") {
            description("长句持续说话，验证 USER_SPEAKING 期间音频流不中断")
            chineseSpeech(
                "我想了解一下你们这里有没有什么适合周末出去玩的地方可以推荐给我",
                initialSoftness = 1.0f
            )
            expectStt("我想了解一下你们这里有没有什么适合周末出去玩的地方可以推荐给我")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            timeout(60000)
            withTag("basic")
            withTag("long")
        }
    )
}
