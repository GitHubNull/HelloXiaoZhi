package org.oxff.helloxiaozhi.asr.suites

import com.google.gson.Gson
import org.oxff.helloxiaozhi.asr.AsrTestRunner
import org.oxff.helloxiaozhi.asr.AsrTestScenario
import org.oxff.helloxiaozhi.asr.Trigger
import org.oxff.helloxiaozhi.asr.scenario
import org.oxff.helloxiaozhi.chat.ChatState
import java.io.File

/**
 * 真实世界语音测试套件：使用从开放平台（B 站等）下载的真实语音。
 *
 * 测试用例来自 `app/src/test/resources/asr/test_cases/real_world_cases.json`
 * （由 `tools/asr/audio_pipeline.py` 下载视频、提取音频、对齐字幕后生成）。
 *
 * 音频素材尚未准备时本套件自动为空（不阻塞其他套件运行）；
 * 素材生成命令参见 `tools/asr/README.md`。
 */
object RealWorldSpeechSuite {

    /** 测试用例 JSON 结构 */
    private data class RealWorldCase(
        val name: String = "",
        val description: String = "",
        val audio_file: String = "",
        val subtitle_file: String = "",
        val expected_text: String = "",
        val start_ms: Int = 0,
        val duration_ms: Int = 0,
        val tags: List<String> = emptyList()
    )

    val scenarios: List<AsrTestScenario> = loadCases()

    private fun loadCases(): List<AsrTestScenario> {
        val casesFile = File(AsrTestRunner.defaultResourceRoot(), "test_cases/real_world_cases.json")
        if (!casesFile.exists()) return emptyList()

        val cases = try {
            Gson().fromJson(casesFile.readText(Charsets.UTF_8), Array<RealWorldCase>::class.java)
                ?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        return cases.filter { it.audio_file.isNotEmpty() }.map { case ->
            scenario(case.name) {
                description(case.description.ifEmpty { "真实世界语音片段：${case.audio_file}" })
                realWorldInput(
                    audioFile = case.audio_file,
                    subtitleFile = case.subtitle_file,
                    startMs = case.start_ms,
                    durationMs = case.duration_ms
                )
                expectStt(case.expected_text)
                expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                    Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
                expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                    Trigger.SILENCE_TIMEOUT, 2000)
                withTag("real_world")
                case.tags.forEach { withTag(it) }
            }
        }
    }
}
