package org.oxff.helloxiaozhi.asr.suites

import org.oxff.helloxiaozhi.asr.AsrTestScenario
import org.oxff.helloxiaozhi.asr.Trigger
import org.oxff.helloxiaozhi.asr.scenario
import org.oxff.helloxiaozhi.chat.ChatState

/**
 * 压力测试套件：验证长时间/高频次交互的稳定性。
 *
 * 场景携带特殊标签：
 *  - "responseDelay:<毫秒>"：执行器据此设置模拟服务器响应延迟，
 *    模拟网络延迟波动场景。
 */
object StressSuite {

    val scenarios: List<AsrTestScenario> = buildList {
        // 场景 1：连续多轮对话（单次执行内以长句模拟持续说话）
        for (round in 1..10) {
            add(scenario("压力_连续对话_第${round}轮") {
                description("连续对话压力测试第 $round 轮，验证状态机反复切换稳定")
                chineseSpeech("这是连续对话压力测试的第${round}轮内容", initialSoftness = 1.0f)
                expectStt("这是连续对话压力测试的第${round}轮内容")
                expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                    Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
                expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                    Trigger.SILENCE_TIMEOUT, 2000)
                withTag("stress")
            })
        }

        // 场景 2：高频次短句交互
        val shortPhrases = listOf("好的", "谢谢", "继续", "换一个", "停")
        for (phrase in shortPhrases) {
            add(scenario("压力_高频短句_$phrase") {
                description("高频短句交互：「$phrase」")
                chineseSpeech(phrase, initialSoftness = 0.9f)
                expectStt(phrase)
                expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                    Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
                expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                    Trigger.SILENCE_TIMEOUT, 2000)
                withTag("stress")
                withTag("short")
            })
        }

        // 场景 3：网络延迟波动（模拟服务器 1.5s 处理延迟）
        add(scenario("压力_网络延迟波动_1500ms") {
            description("模拟服务器 1500ms 响应延迟，验证客户端链路不受影响")
            chineseSpeech("网络有点慢你还能听到吗", initialSoftness = 1.0f)
            expectStt("网络有点慢你还能听到吗")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            timeout(15000)
            withTag("stress")
            withTag("responseDelay:1500")
        })
    }
}
