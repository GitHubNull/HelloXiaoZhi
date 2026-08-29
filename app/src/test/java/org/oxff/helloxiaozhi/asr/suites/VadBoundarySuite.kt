package org.oxff.helloxiaozhi.asr.suites

import org.oxff.helloxiaozhi.asr.AsrTestScenario
import org.oxff.helloxiaozhi.asr.SyntheticAudioSource
import org.oxff.helloxiaozhi.asr.Trigger
import org.oxff.helloxiaozhi.asr.scenario
import org.oxff.helloxiaozhi.chat.ChatState

/**
 * VAD 边界测试套件：验证语音活动检测的阈值与防抖机制。
 *
 * 对齐 ChatStateMachine 常量：
 *  - THRESHOLD_SPEAKING = 0.015（严格大于才触发）
 *  - REQUIRED_SPEAKING_FRAMES = 4（4 帧防抖）
 *
 * 合成信号的电平口径与 AudioMath.rmsLevel 一致（绝对均值/32768）。
 */
object VadBoundarySuite {

    val scenarios: List<AsrTestScenario> = listOf(
        // 场景 1：电平恰好等于阈值（0.015，不满足严格大于，不应触发）
        scenario("VAD_阈值边界_恰好等于") {
            description("恒定电平 0.015 恰好等于阈值，验证严格大于判定")
            syntheticInput(SyntheticAudioSource.AudioPattern.CONSTANT_LEVEL, 1500, 0.015f)
            expectStt("")
            withTag("vad")
            withTag("boundary")
        },

        // 场景 2：电平略低于阈值
        scenario("VAD_阈值边界_略低于") {
            description("恒定电平 0.012 略低于阈值，不应触发")
            syntheticInput(SyntheticAudioSource.AudioPattern.CONSTANT_LEVEL, 1500, 0.012f)
            expectStt("")
            withTag("vad")
            withTag("boundary")
        },

        // 场景 3：电平略高于阈值（应触发）
        scenario("VAD_阈值边界_略高于") {
            description("恒定电平 0.02 略高于阈值，应在 4 帧防抖后触发")
            syntheticInput(SyntheticAudioSource.AudioPattern.CONSTANT_LEVEL, 1500, 0.02f)
            expectStt("测试")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("vad")
            withTag("boundary")
        },

        // 场景 4：瞬时噪声脉冲（单帧高电平，防抖应过滤）
        scenario("VAD_防抖_瞬时脉冲") {
            description("每 10 帧一个单帧脉冲，3 帧防抖应全部过滤")
            syntheticInput(SyntheticAudioSource.AudioPattern.PULSE, 3000, 0.5f)
            expectStt("")
            withTag("vad")
            withTag("debounce")
        },

        // 场景 5：持续低电平背景噪声
        scenario("VAD_背景噪声_持续低电平") {
            description("持续 0.01 电平背景噪声，不应误触发")
            syntheticInput(SyntheticAudioSource.AudioPattern.CONSTANT_LEVEL, 5000, 0.01f)
            expectStt("")
            withTag("vad")
            withTag("noise")
        }
    )
}
