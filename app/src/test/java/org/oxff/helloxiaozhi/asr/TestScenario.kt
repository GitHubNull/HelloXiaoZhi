package org.oxff.helloxiaozhi.asr

import org.oxff.helloxiaozhi.chat.ChatState

/**
 * ASR 测试场景定义：描述一个完整的语音交互测试用例。
 */
data class AsrTestScenario(
    val name: String,
    val description: String,
    val audioInput: AudioInputSpec,
    val expectedStt: String,
    val expectedStateTransitions: List<StateTransition>,
    val timeoutMs: Long = 30000,
    val tags: Set<String> = emptySet()
)

/** 音频输入规格 */
sealed class AudioInputSpec {
    /** 预录 WAV 文件 */
    data class WavFile(val path: String) : AudioInputSpec()

    /** 合成音频 */
    data class Synthetic(
        val pattern: SyntheticAudioSource.AudioPattern,
        val durationMs: Int,
        val baseLevel: Float
    ) : AudioInputSpec()

    /** 中文语音片段（句首轻声场景） */
    data class ChineseSpeech(
        val text: String,
        val initialSoftness: Float,  // 句首轻声强度 0..1
        val speechRate: Float        // 语速（帧/字）
    ) : AudioInputSpec()

    /** 噪声环境 */
    data class Noisy(
        val cleanAudio: AudioInputSpec,
        val noiseLevel: Float,
        val noiseType: NoisyAudioSource.NoiseType
    ) : AudioInputSpec()

    /** 真实世界音频 */
    data class RealWorld(
        val audioFile: String,
        val subtitleFile: String,
        val startMs: Int,
        val durationMs: Int
    ) : AudioInputSpec()
}

/** 期望的状态迁移 */
data class StateTransition(
    val from: ChatState,
    val to: ChatState,
    val trigger: Trigger,
    val maxDelayMs: Long
)

enum class Trigger {
    AUDIO_LEVEL_ABOVE_THRESHOLD,
    AUDIO_LEVEL_BELOW_THRESHOLD,
    SILENCE_TIMEOUT,
    USER_INTERRUPT,
    SERVER_TTS_START,
    SERVER_TTS_STOP
}

/** 场景构建器（DSL） */
class AsrScenarioBuilder {
    private var name: String = ""
    private var description: String = ""
    private var audioInput: AudioInputSpec? = null
    private var expectedStt: String = ""
    private val expectedStateTransitions = mutableListOf<StateTransition>()
    private var timeoutMs: Long = 30000
    private val tags = mutableSetOf<String>()

    fun name(name: String) {
        this.name = name
    }

    fun description(description: String) {
        this.description = description
    }

    fun wavInput(path: String) {
        this.audioInput = AudioInputSpec.WavFile(path)
    }

    fun syntheticInput(
        pattern: SyntheticAudioSource.AudioPattern,
        durationMs: Int,
        level: Float
    ) {
        this.audioInput = AudioInputSpec.Synthetic(pattern, durationMs, level)
    }

    fun chineseSpeech(text: String, initialSoftness: Float = 0.5f) {
        this.audioInput = AudioInputSpec.ChineseSpeech(text, initialSoftness, 3.0f)
    }

    fun noisyInput(
        cleanAudio: AudioInputSpec,
        noiseLevel: Float,
        noiseType: NoisyAudioSource.NoiseType
    ) {
        this.audioInput = AudioInputSpec.Noisy(cleanAudio, noiseLevel, noiseType)
    }

    fun realWorldInput(
        audioFile: String,
        subtitleFile: String,
        startMs: Int,
        durationMs: Int
    ) {
        this.audioInput = AudioInputSpec.RealWorld(audioFile, subtitleFile, startMs, durationMs)
    }

    fun expectStt(text: String) {
        this.expectedStt = text
    }

    fun expectTransition(
        from: ChatState,
        to: ChatState,
        trigger: Trigger,
        maxDelayMs: Long
    ) {
        this.expectedStateTransitions.add(StateTransition(from, to, trigger, maxDelayMs))
    }

    fun timeout(ms: Long) {
        this.timeoutMs = ms
    }

    fun withTag(tag: String) {
        this.tags.add(tag)
    }

    fun build(): AsrTestScenario {
        require(name.isNotEmpty()) { "Scenario name is required" }
        require(audioInput != null) { "Audio input is required" }
        return AsrTestScenario(
            name = name,
            description = description,
            audioInput = audioInput!!,
            expectedStt = expectedStt,
            expectedStateTransitions = expectedStateTransitions,
            timeoutMs = timeoutMs,
            tags = tags
        )
    }
}

/** 创建测试场景的 DSL 入口 */
fun scenario(name: String, block: AsrScenarioBuilder.() -> Unit): AsrTestScenario {
    val builder = AsrScenarioBuilder()
    builder.name(name)
    builder.block()
    return builder.build()
}
