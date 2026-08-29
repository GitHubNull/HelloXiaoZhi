package org.oxff.helloxiaozhi.asr.suites

import org.oxff.helloxiaozhi.asr.AsrTestScenario
import org.oxff.helloxiaozhi.asr.AudioInputSpec
import org.oxff.helloxiaozhi.asr.NoisyAudioSource
import org.oxff.helloxiaozhi.asr.Trigger
import org.oxff.helloxiaozhi.asr.scenario
import org.oxff.helloxiaozhi.chat.ChatState

/**
 * 噪声鲁棒性测试套件：验证不同噪声环境下的语音识别能力。
 *
 * 测试方法：在干净合成语音上叠加可控噪声（[NoisyAudioSource]），
 * 观察信噪比梯度下 VAD 触发与识别链路的表现。
 */
object NoiseRobustnessSuite {

    private fun cleanSpeech(text: String) =
        AudioInputSpec.ChineseSpeech(text = text, initialSoftness = 1.0f, speechRate = 3.0f)

    val scenarios: List<AsrTestScenario> = buildList {
        // 场景 1：白噪声背景（不同信噪比梯度）
        for (noiseLevel in listOf(0.02f, 0.05f, 0.1f)) {
            add(scenario("噪声_白噪声_强度${"%.2f".format(noiseLevel)}") {
                description("白噪声电平 $noiseLevel 叠加在正常语音上")
                noisyInput(cleanSpeech("今天天气怎么样"), noiseLevel, NoisyAudioSource.NoiseType.WHITE)
                expectStt("今天天气怎么样")
                expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                    Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
                expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                    Trigger.SILENCE_TIMEOUT, 2000)
                withTag("noise")
                withTag("white")
            })
        }

        // 场景 2：街道噪声背景（低频隆隆声 + 白噪声）
        add(scenario("噪声_街道环境") {
            description("街道噪声（低频隆隆声）叠加")
            noisyInput(cleanSpeech("我想去附近的公园"), 0.05f, NoisyAudioSource.NoiseType.STREET)
            expectStt("我想去附近的公园")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("noise")
            withTag("street")
        })

        // 场景 3：多人说话背景（babble 噪声）
        add(scenario("噪声_多人交谈背景") {
            description("多人交谈背景噪声叠加，模拟嘈杂室内")
            noisyInput(cleanSpeech("请帮我定一个闹钟"), 0.05f, NoisyAudioSource.NoiseType.BABBLE)
            expectStt("请帮我定一个闹钟")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("noise")
            withTag("babble")
        })

        // 场景 4：纯噪声误触发验证（无人说话）
        add(scenario("噪声_纯白噪声_不应触发") {
            description("仅白噪声无人说话，验证不误触发")
            noisyInput(
                AudioInputSpec.Synthetic(
                    org.oxff.helloxiaozhi.asr.SyntheticAudioSource.AudioPattern.SILENCE, 3000, 0f
                ),
                0.01f,
                NoisyAudioSource.NoiseType.WHITE
            )
            expectStt("")
            withTag("noise")
            withTag("false_trigger")
        })

        // 场景 5：空调外机噪声叠加语音（信噪比梯度）
        // AC 模型：50/100Hz 风机哼声 + 低通气流噪声 + 压缩机启停包络（见 NoisyAudioSource）
        for (noiseLevel in listOf(0.05f, 0.1f)) {
            add(scenario("噪声_空调外机_语音叠加_强度${"%.2f".format(noiseLevel)}") {
                description("空调外机噪声电平 $noiseLevel 叠加在正常语音上")
                noisyInput(cleanSpeech("打开客厅的灯"), noiseLevel, NoisyAudioSource.NoiseType.AC_OUTDOOR)
                expectStt("打开客厅的灯")
                expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                    Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
                expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                    Trigger.SILENCE_TIMEOUT, 2000)
                withTag("noise")
                withTag("ac_outdoor")
            })
        }

        // 场景 6：纯空调外机噪声（中低电平）不应触发——客户端 VAD 门控验证。
        // 实体机“没人说话却识别出嗯/对”的前提是客户端把噪声上行了；
        // hallucinationStt 标签注入服务器幻觉词，若客户端门控有效则永不会收到该词。
        add(scenario("噪声_空调外机_纯噪声_不应触发") {
            description("纯空调外机噪声 6 秒（电平 0.03，RMS 低于触发阈值 0.02），期望不上行任何音频")
            noisyInput(
                AudioInputSpec.Synthetic(
                    org.oxff.helloxiaozhi.asr.SyntheticAudioSource.AudioPattern.SILENCE, 6000, 0f
                ),
                0.03f,
                NoisyAudioSource.NoiseType.AC_OUTDOOR
            )
            expectStt("")
            withTag("noise")
            withTag("ac_outdoor")
            withTag("false_trigger")
            withTag("hallucinationStt:嗯")
        })

        // 场景 7：强空调外机噪声误触发 + 幻觉词复现。
        // 电平 0.15 时噪声 RMS ≈ 0.024 超过阈值，状态机会开门上行纯噪声，
        // 服务器（模拟真实 ASR 引擎）回吐幻觉词“嗯”——复现实体机误识别链路，
        // 评分与瓶颈诊断（NOISE_SENSITIVITY）量化该风险。
        add(scenario("噪声_空调外机_强电平_误触发幻觉词") {
            description("强空调外机噪声 6 秒（电平 0.15，RMS 超阈值），复现误触发+幻觉词“嗯”")
            noisyInput(
                AudioInputSpec.Synthetic(
                    org.oxff.helloxiaozhi.asr.SyntheticAudioSource.AudioPattern.SILENCE, 6000, 0f
                ),
                0.15f,
                NoisyAudioSource.NoiseType.AC_OUTDOOR
            )
            expectStt("")
            withTag("noise")
            withTag("ac_outdoor")
            withTag("false_trigger")
            withTag("hallucinationStt:嗯")
        })
    }
}
