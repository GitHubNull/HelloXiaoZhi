package org.oxff.helloxiaozhi.asr

/**
 * 瓶颈分析器：根据评估报告自动识别性能瓶颈，输出可执行的优化建议。
 *
 * 诊断规则基于分层归因：
 *  - 客户端采集/状态机层：VAD 阈值、预触发缓冲、触发延迟
 *  - 传输/编码层：音频电平保持、频谱失真
 *  - 网络/服务器层：端到端延迟
 */
object BottleneckAnalyzer {

    enum class Category {
        VAD_THRESHOLD_TOO_HIGH,      // VAD 阈值过高导致句首丢失
        VAD_THRESHOLD_TOO_LOW,       // VAD 阈值过低导致误触发
        PRE_ROLL_INSUFFICIENT,       // 预触发缓冲不足
        OPUS_QUALITY_DEGRADATION,    // Opus 编解码质量下降
        NETWORK_LATENCY,             // 网络延迟过高
        STATE_MACHINE_DELAY,         // 状态机响应延迟
        AUDIO_GAIN_ISSUE,            // 音频增益问题
        NOISE_SENSITIVITY            // 噪声敏感度过高
    }

    enum class Severity { CRITICAL, HIGH, MEDIUM, LOW }

    data class Bottleneck(
        val category: Category,
        val severity: Severity,
        val description: String,
        val recommendation: String,
        val affectedScenarios: List<String>
    )

    /** 分析单份评估报告 */
    fun analyze(report: AsrEvaluationReport): List<Bottleneck> =
        analyzeAll(listOf(report))

    /** 批量分析：聚合多份报告，按类别归并受影响场景 */
    fun analyzeAll(reports: List<AsrEvaluationReport>): List<Bottleneck> {
        val findings = mutableMapOf<Category, MutableList<Pair<Severity, AsrEvaluationReport>>>()

        for (report in reports) {
            for ((category, severity) in diagnose(report)) {
                findings.getOrPut(category) { mutableListOf() }.add(severity to report)
            }
        }

        return findings.map { (category, entries) ->
            val scenarios = entries.map { it.second.scenarioName }.distinct()
            Bottleneck(
                category = category,
                severity = entries.maxOf { it.first },
                description = describe(category, entries.size),
                recommendation = recommend(category),
                affectedScenarios = scenarios
            )
        }.sortedBy { it.severity.ordinal }
    }

    /** 单报告诊断：返回（类别, 严重度）列表 */
    private fun diagnose(report: AsrEvaluationReport): List<Pair<Category, Severity>> {
        val result = mutableListOf<Pair<Category, Severity>>()

        // 1. VAD 漏触发（召回率不足）→ 阈值过高 / 句首轻声丢失
        if (report.vadMetrics.falseNegativeRate > 0.05f || report.vadMetrics.recall < 0.95f) {
            val severity = if (report.vadMetrics.recall < 0.8f) Severity.CRITICAL else Severity.HIGH
            result.add(Category.VAD_THRESHOLD_TOO_HIGH to severity)
        }

        // 2. VAD 误触发（精确率不足）→ 阈值过低 / 噪声敏感
        if (report.vadMetrics.falsePositiveRate > 0.05f || report.vadMetrics.precision < 0.95f) {
            val severity = if (report.vadMetrics.precision < 0.8f) Severity.HIGH else Severity.MEDIUM
            result.add(Category.VAD_THRESHOLD_TOO_LOW to severity)
            if (report.scenarioName.contains("噪声") || report.scenarioName.contains("NOISE")) {
                result.add(Category.NOISE_SENSITIVITY to severity)
            }
        }

        // 3. 识别结果缺失句首（识别有结果但句首字丢失）→ 预触发缓冲不足
        if (report.referenceText.isNotEmpty() && report.hypothesisText.isNotEmpty()) {
            val ref = report.referenceText
            val hyp = report.hypothesisText
            val headLost = ref.length >= 2 && !hyp.startsWith(ref.first().toString()) &&
                hyp.length < ref.length && ref.endsWith(hyp.takeLast(minOf(hyp.length, ref.length - 1)))
            if (headLost) {
                result.add(Category.PRE_ROLL_INSUFFICIENT to Severity.CRITICAL)
            }
        }

        // 4. 电平保持度差 → 音频增益问题或编码退化
        if (report.audioQuality.levelPreservation < 0.8f && report.hypothesisText.isNotEmpty()) {
            result.add(Category.AUDIO_GAIN_ISSUE to Severity.HIGH)
        }
        if (report.audioQuality.snr in 0f..10f && report.hypothesisText.isNotEmpty()) {
            result.add(Category.OPUS_QUALITY_DEGRADATION to Severity.MEDIUM)
        }

        // 5. 端到端延迟过高 → 网络延迟
        if (report.endToEndLatencyMs > 2000) {
            val severity = if (report.endToEndLatencyMs > 3000) Severity.CRITICAL else Severity.HIGH
            result.add(Category.NETWORK_LATENCY to severity)
        }

        // 6. VAD 触发延迟过高 → 状态机响应延迟
        if (report.vadTriggerDelayMs in 501..Long.MAX_VALUE) {
            result.add(Category.STATE_MACHINE_DELAY to Severity.MEDIUM)
        }

        return result
    }

    private fun describe(category: Category, scenarioCount: Int): String = when (category) {
        Category.VAD_THRESHOLD_TOO_HIGH ->
            "VAD 触发阈值偏高：存在漏触发（召回率不足），句首轻声或低音量语音未能触发 USER_SPEAKING（影响 $scenarioCount 个场景）"
        Category.VAD_THRESHOLD_TOO_LOW ->
            "VAD 触发阈值偏低：存在误触发（精确率不足），背景噪声被误判为用户说话（影响 $scenarioCount 个场景）"
        Category.PRE_ROLL_INSUFFICIENT ->
            "预触发缓冲不足：服务器收到的音频缺失句首，识别结果丢失开头字（影响 $scenarioCount 个场景）"
        Category.OPUS_QUALITY_DEGRADATION ->
            "音频保真度下降：上行音频信噪比偏低，编码/重采样链路可能引入失真（影响 $scenarioCount 个场景）"
        Category.NETWORK_LATENCY ->
            "端到端延迟过高：从音频结束到收到 STT 结果超过 2 秒（影响 $scenarioCount 个场景）"
        Category.STATE_MACHINE_DELAY ->
            "状态机响应延迟：从开口到进入 USER_SPEAKING 超过 500ms，防抖帧数或线程调度存在瓶颈（影响 $scenarioCount 个场景）"
        Category.AUDIO_GAIN_ISSUE ->
            "音频增益异常：服务器收到的音频电平明显低于输入，上行增益设置或采集音量不足（影响 $scenarioCount 个场景）"
        Category.NOISE_SENSITIVITY ->
            "噪声敏感度过高：噪声环境下触发率/识别率显著下降（影响 $scenarioCount 个场景）"
    }

    private fun recommend(category: Category): String = when (category) {
        Category.VAD_THRESHOLD_TOO_HIGH ->
            "下调 ChatStateMachine.THRESHOLD_SPEAKING（当前 0.02），或增强录音上行增益；优先检查句首轻声场景的电平分布"
        Category.VAD_THRESHOLD_TOO_LOW ->
            "上调 THRESHOLD_SPEAKING 或增加 REQUIRED_SPEAKING_FRAMES 防抖帧数；评估启用系统降噪（VOICE_COMMUNICATION 源）的可行性"
        Category.PRE_ROLL_INSUFFICIENT ->
            "增大 ChatStateMachine.PRE_ROLL_FRAMES（当前 16 帧/960ms）；检查 flushPreRoll 是否在 listen start 之前执行"
        Category.OPUS_QUALITY_DEGRADATION ->
            "检查 OpusCodec 编码码率与 44.1k→16k 重采样器（LinearResampler）质量；验证帧对齐（960 采样）"
        Category.NETWORK_LATENCY ->
            "检查服务器端 STT 处理耗时与网络往返；评估启用连接复用与就近节点"
        Category.STATE_MACHINE_DELAY ->
            "检查 UiExecutor 线程调度是否被阻塞；确认 REQUIRED_SPEAKING_FRAMES 防抖帧数（当前 3 帧/180ms）是否过大"
        Category.AUDIO_GAIN_ISSUE ->
            "检查 AudioRecorderManager 上行增益配置与麦克风采集音量；确认服务器侧是否有自动增益"
        Category.NOISE_SENSITIVITY ->
            "评估启用系统 AEC/NS（AudioSource.VOICE_COMMUNICATION + AcousticEchoCanceler/NoiseSuppressor）；或提高打断阈值防抖"
    }
}
