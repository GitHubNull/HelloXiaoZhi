package org.oxff.helloxiaozhi.asr.suites

import org.oxff.helloxiaozhi.asr.AsrTestScenario
import org.oxff.helloxiaozhi.asr.Trigger
import org.oxff.helloxiaozhi.asr.scenario
import org.oxff.helloxiaozhi.chat.ChatState

/**
 * 中文句首轻声专项测试套件（核心痛点）。
 *
 * 背景（对齐 ChatStateMachine.THRESHOLD_SPEAKING 注释）：
 * 中文句首轻声（如"你"、"今"、"晚"）电平常在 0.02~0.09 之间，
 * 若句首轻声段未进入预触发缓冲，服务器只收到后半句
 * （如"你猜"只识别到"猜"）。本套件以不同轻声强度梯度验证：
 *  - 触发完整性（能否进入 USER_SPEAKING）
 *  - 句首完整性（预触发缓冲是否补发了轻声段音频）
 */
object ChineseSoftnessSuite {

    /** 轻声强度梯度：0.3 极弱 → 1.0 正常 */
    private val softnessLevels = listOf(0.3f, 0.4f, 0.6f, 0.8f, 1.0f)

    val scenarios: List<AsrTestScenario> = buildList {
        // 场景组 1：「你好」轻声梯度
        for (softness in softnessLevels) {
            add(scenario("句首轻声_你好_强度${"%.1f".format(softness)}") {
                description("「你」轻声强度 $softness，验证句首轻声触发与完整性")
                chineseSpeech("你好", initialSoftness = softness)
                expectStt("你好")
                expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                    Trigger.SERVER_VAD, 500)
                expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                    Trigger.SERVER_TTS_START, 2000)
                expectTransition(ChatState.AI_SPEAKING, ChatState.IDLE,
                    Trigger.SERVER_TTS_STOP, 2000)
                withTag("softness")
            })
        }

        // 场景 2：多字句的句首轻声
        add(scenario("句首轻声_今天天气怎么样") {
            description("「今」轻声，多字句场景")
            chineseSpeech("今天天气怎么样", initialSoftness = 0.4f)
            expectStt("今天天气怎么样")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.SERVER_VAD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SERVER_TTS_START, 2000)
            withTag("softness")
        })

        add(scenario("句首轻声_我想问一下") {
            description("「我」轻声，口语化短句")
            chineseSpeech("我想问一下", initialSoftness = 0.35f)
            expectStt("我想问一下")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.SERVER_VAD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SERVER_TTS_START, 2000)
            withTag("softness")
        })

        // 场景 3：连续轻声词组（句首两个轻声字）
        add(scenario("句首轻声_连续轻声_爸爸妈妈") {
            description("句首连续轻声词组，验证轻声段持续低电平下的稳定性")
            chineseSpeech("爸爸妈妈在吗", initialSoftness = 0.45f)
            expectStt("爸爸妈妈在吗")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.SERVER_VAD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SERVER_TTS_START, 2000)
            withTag("softness")
        })
    }
}
