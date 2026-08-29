package org.oxff.helloxiaozhi.asr

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.oxff.helloxiaozhi.asr.suites.BasicFunctionalitySuite
import org.oxff.helloxiaozhi.asr.suites.ChineseSoftnessSuite
import org.oxff.helloxiaozhi.asr.suites.InterruptionSuite
import org.oxff.helloxiaozhi.asr.suites.NoiseRobustnessSuite
import org.oxff.helloxiaozhi.asr.suites.RealWorldSpeechSuite
import org.oxff.helloxiaozhi.asr.suites.StressSuite
import org.oxff.helloxiaozhi.asr.suites.VadBoundarySuite
import org.oxff.helloxiaozhi.chat.ChatEvent
import org.oxff.helloxiaozhi.chat.ChatState
import org.oxff.helloxiaozhi.chat.ChatStateMachine
import org.oxff.helloxiaozhi.util.AudioMath
import org.oxff.helloxiaozhi.util.DirectExecutor
import org.oxff.helloxiaozhi.util.SilenceScheduler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ASR 测试执行器：编排虚拟音频源、模拟服务器、被测状态机，执行测试场景。
 *
 * 非侵入性设计：
 *  - 不修改任何业务代码；被测对象是生产类 [org.oxff.helloxiaozhi.chat.ChatStateMachine]
 *  - 通过 [DirectExecutor] 与 [VirtualSilenceScheduler] 实现确定性执行（无真实时钟等待）
 *  - 音频帧以原始 PCM 二进制帧上行（JVM 环境无 Opus JNI，协议时序与生产一致）
 */
class AsrTestRunner(
    private val outputDir: File,
    private val resourceRoot: File = defaultResourceRoot()
) {

    // ---------------- 虚拟时钟 ----------------

    /**
     * 虚拟静音调度器：把状态机的 1000ms 静音计时映射到虚拟时钟，
     * 测试按帧推进时间（每帧 60ms），无需真实等待，执行快速且可复现。
     */
    class VirtualSilenceScheduler : SilenceScheduler {
        var clockMs = 0L
            private set
        private var fireAt = -1L
        private var action: (() -> Unit)? = null

        override fun schedule(delayMs: Long, action: () -> Unit) {
            fireAt = clockMs + delayMs
            this.action = action
        }

        override fun cancel() {
            fireAt = -1
            action = null
        }

        /** 推进虚拟时间；到期则触发静音计时动作 */
        fun advanceTo(nowMs: Long) {
            clockMs = nowMs
            if (fireAt in 0..nowMs) {
                val pending = action
                fireAt = -1
                action = null
                pending?.invoke()
            }
        }
    }

    // ---------------- 客户端测试线束 ----------------

    /** 服务器下行消息记录 */
    data class DownlinkMessage(val timestampNano: Long, val type: String, val text: String, val raw: String)

    /**
     * 测试线束：WebSocket 客户端 + 状态机组装。
     * 对应生产环境 XiaoZhiController 的组装职责（仅协议层，不触碰 UI/音频硬件）。
     */
    inner class TestClientHarness(private val server: MockAsrServer) : ChatStateMachine.Callbacks {

        private val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        private var webSocket: WebSocket? = null

        private val gson = Gson()
        private val helloLatch = CountDownLatch(1)
        val sttLatch = CountDownLatch(1)

        @Volatile var serverSessionId = ""
            private set

        @Volatile var lastAudioEndNano = 0L

        @Volatile var sttReceivedNano = 0L

        /** 收到的 STT 文本（识别结果回显） */
        @Volatile var receivedStt: String? = null
            private set

        /** 状态机引用（由 runScenario 注入，用于服务器消息驱动状态迁移） */
        @Volatile var stateMachine: ChatStateMachine? = null

        val downlinkMessages = ConcurrentLinkedQueue<DownlinkMessage>()

        fun connect(): Boolean {
            val request = Request.Builder()
                .url(server.wsUrl())
                .header("Device-Id", "AA:BB:CC:DD:EE:FF")
                .header("Client-Id", "asr-test-client")
                .header("Protocol-Version", "1")
                .build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = Unit

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val parsed = try {
                        gson.fromJson(text, Map::class.java)
                    } catch (_: Exception) {
                        null
                    }
                    val type = parsed?.get("type") as? String ?: "unknown"
                    val msgText = parsed?.get("text") as? String ?: ""
                    downlinkMessages.add(DownlinkMessage(System.nanoTime(), type, msgText, text))
                    when (type) {
                        "hello" -> {
                            serverSessionId = parsed?.get("session_id") as? String ?: ""
                            helloLatch.countDown()
                        }
                        "stt" -> {
                            receivedStt = msgText
                            sttReceivedNano = System.nanoTime()
                            sttLatch.countDown()
                            // 服务器端 VAD 驱动：收到 STT → 进入 USER_SPEAKING
                            stateMachine?.setState(ChatState.USER_SPEAKING)
                        }
                        "tts" -> {
                            val state = parsed?.get("state") as? String
                            when (state) {
                                "start" -> {
                                    // 服务器端 VAD 驱动：收到 TTS start → 进入 AI_SPEAKING
                                    stateMachine?.setState(ChatState.AI_SPEAKING)
                                }
                                "stop" -> {
                                    // 服务器端 VAD 驱动：收到 TTS stop → 回到 IDLE
                                    stateMachine?.setState(ChatState.IDLE)
                                }
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    helloLatch.countDown()
                    sttLatch.countDown()
                }
            })
            // 对齐生产链路（XiaoZhiWebSocket.onOpen 后立即发 HelloMessage）；
            // OkHttp 会在握手完成后自动按序发出此消息
            webSocket?.send(gson.toJson(mapOf(
                "type" to "hello", "version" to 1, "transport" to "websocket",
                "response_mode" to "manual",
                "audio_params" to mapOf(
                    "format" to "opus", "sample_rate" to 16000,
                    "channels" to 1, "frame_duration" to 60
                )
            )))
            return helloLatch.await(5, TimeUnit.SECONDS)
        }

        fun close() {
            webSocket?.close(1000, "test done")
            client.dispatcher.executorService.shutdown()
        }

        // ---- ChatStateMachine.Callbacks（生产链路的测试等价实现） ----

        override fun sendAudioData(frame: ShortArray) {
            // 生产链路此处经 OpusCodec.encode 后发送；JVM 测试无 JNI，
            // 直接发送原始 PCM（小端 int16），帧时序与协议一致
            val buffer = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in frame) buffer.putShort(s)
            webSocket?.send(ByteString.of(*buffer.array()))
        }

        override fun sendTextData(message: Any) {
            webSocket?.send(gson.toJson(message))
        }

        override fun getSessionId(): String = serverSessionId

        override fun onEvent(event: ChatEvent) = Unit
    }

    // ---------------- 场景执行 ----------------

    /** 执行单个测试场景 */
    fun runScenario(scenario: AsrTestScenario): AsrEvaluationReport {
        val server = MockAsrServer()
        server.start()
        if (scenario.expectedStt.isNotEmpty()) {
            // 模拟服务器「完美识别」：评估客户端上行链路是否把完整语音送达
            server.setDefaultSttResponse(scenario.expectedStt)
        }
        // 标签 "hallucinationStt:<词>"：注入服务器幻觉词（模拟真实 ASR 引擎
        // 把背景噪声误识别为“嗯/对”等短词，复现实体机常见误识别）
        scenario.tags.firstOrNull { it.startsWith("hallucinationStt:") }
            ?.substringAfter(":")
            ?.let { server.setDefaultSttResponse(it) }
        // 标签 "responseDelay:<毫秒>"：模拟网络/服务器处理延迟
        scenario.tags.firstOrNull { it.startsWith("responseDelay:") }
            ?.substringAfter(":")?.toLongOrNull()
            ?.let { server.setResponseDelay(it) }

        val harness = TestClientHarness(server)
        check(harness.connect()) { "测试线束连接模拟服务器失败: ${server.wsUrl()}" }

        val scheduler = VirtualSilenceScheduler()
        val machine = ChatStateMachine(DirectExecutor(), scheduler, harness)
        harness.stateMachine = machine

        // 记录实际状态迁移（虚拟时间戳）
        // 线程安全：onStateChanged 在 WebSocket 回调线程触发，评估在主线程进行
        val actualTransitions = java.util.concurrent.CopyOnWriteArrayList<StateTransition>()
        var previousState = ChatState.IDLE

        // 服务器端 VAD 驱动：状态迁移由服务器消息触发，而非客户端电平检测
        machine.onStateChanged = { newState ->
            actualTransitions.add(
                StateTransition(previousState, newState, Trigger.SERVER_VAD, scheduler.clockMs)
            )
            previousState = newState
        }

        val source = createAudioSource(scenario.audioInput)
        val originalPcm = drainAllPcm(source)
        source.reset()

        val startNano = System.nanoTime()
        // 逐帧喂给状态机（每帧 60ms 虚拟时间）
        var frame: ShortArray? = source.readFrame()
        while (frame != null) {
            val level = AudioMath.rmsLevel(frame)
            machine.handleAudioLevel(level, frame)
            scheduler.advanceTo(scheduler.clockMs + FRAME_MS)
            frame = source.readFrame()
        }
        // 输入结束后补静音帧，驱动服务器端 VAD 检测用户停止说话
        val silentFrame = ShortArray(FRAME_SIZE)
        repeat(TRAILING_SILENCE_FRAMES) {
            machine.handleAudioLevel(0f, silentFrame)
            scheduler.advanceTo(scheduler.clockMs + FRAME_MS)
        }
        harness.lastAudioEndNano = System.nanoTime()
        val feedElapsedNano = System.nanoTime() - startNano

        // 等待服务器端 VAD 检测完成（STT + TTS 序列发送完毕）
        // 服务器收到首个音频帧后自动开始说话段，延迟 responseDelayMs 后发送 STT
        harness.sttLatch.await(scenario.timeoutMs, TimeUnit.MILLISECONDS)
        // sttLatch 在 STT 消息处理中 countDown，但 TTS start/stop 消息可能还在队列中；
        // 短暂等待确保所有下行消息处理完毕（状态迁移完成）
        Thread.sleep(200)

        // ---- 汇总评估 ----
        val hypothesis = harness.receivedStt ?: ""
        val reference = scenario.expectedStt
        val cer = MetricsEngine.calculateCER(reference, hypothesis)
        val wer = MetricsEngine.calculateWER(reference, hypothesis)
        val sentenceAccuracy = MetricsEngine.calculateSentenceAccuracy(reference, hypothesis)

        val vadMetrics = MetricsEngine.calculateVadAccuracy(
            scenario.expectedStateTransitions, actualTransitions
        )

        val e2eLatencyMs = if (harness.sttReceivedNano > 0 && harness.lastAudioEndNano > 0) {
            (harness.sttReceivedNano - harness.lastAudioEndNano) / 1_000_000
        } else -1L

        val firstTrigger = actualTransitions.firstOrNull {
            it.from == ChatState.IDLE && it.to == ChatState.USER_SPEAKING
        }
        val vadTriggerDelayMs = firstTrigger?.maxDelayMs ?: -1L

        val receivedPcm = server.getSegments().flatMap { it.frames }.let { frames ->
            val total = frames.sumOf { it.size }
            ShortArray(total).also { pcm ->
                var offset = 0
                for (f in frames) {
                    System.arraycopy(f, 0, pcm, offset, f.size); offset += f.size
                }
            }
        }
        // 音频质量对比：对称去除首尾静音后比较（排除预触发缓冲/尾部静音的长度差异，
        // 只衡量有声内容的传输保真度）
        val audioQuality = MetricsEngine.calculateAudioQuality(
            trimEdges(originalPcm), trimEdges(receivedPcm)
        )

        val unexpected = actualTransitions.filter { actual ->
            scenario.expectedStateTransitions.none { it.from == actual.from && it.to == actual.to }
        }
        val stateMachineAccuracy = MetricsEngine.calculateVadAccuracy(
            scenario.expectedStateTransitions, actualTransitions
        ).recall.let { recall ->
            if (scenario.expectedStateTransitions.isEmpty()) {
                if (actualTransitions.isEmpty()) 1f else 0f
            } else recall
        }

        val report = AsrEvaluationReport(
            scenarioName = scenario.name,
            timestamp = System.currentTimeMillis(),
            overallScore = 0f,
            cer = cer,
            wer = wer,
            sentenceAccuracy = sentenceAccuracy,
            vadMetrics = vadMetrics,
            endToEndLatencyMs = e2eLatencyMs,
            vadTriggerDelayMs = vadTriggerDelayMs,
            audioQuality = audioQuality,
            stateMachineAccuracy = stateMachineAccuracy,
            unexpectedTransitions = unexpected,
            referenceText = reference,
            hypothesisText = hypothesis,
            audioStats = server.getAudioStats()
        ).let { it.copy(overallScore = ScoreCalculator.overallScore(it)) }

        // 清理
        machine.destroy()
        harness.close()
        server.stop()

        // 附加执行耗时（调试用，写入报告目录）
        outputDir.mkdirs()
        return report
    }

    /** 批量执行测试场景集 */
    fun runScenarios(scenarios: List<AsrTestScenario>): List<AsrEvaluationReport> =
        scenarios.map { runScenario(it) }

    /** 执行预定义的测试套件 */
    fun runTestSuite(suite: TestSuite): TestSuiteReport {
        val reports = runScenarios(suite.scenarios)
        return TestSuiteReport(
            suite = suite.name,
            description = suite.description,
            timestamp = System.currentTimeMillis(),
            reports = reports
        )
    }

    /** 生成 HTML 格式报告 */
    fun generateHtmlReport(reports: List<AsrEvaluationReport>, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        val overall = if (reports.isEmpty()) 0f else reports.map { it.overallScore }.average().toFloat()
        val rows = reports.joinToString("\n") { r ->
            """
            <tr class="${scoreClass(r.overallScore)}">
              <td>${r.scenarioName}</td>
              <td>${"%.1f".format(r.overallScore)}</td>
              <td>${"%.1f%%".format(r.cer * 100)}</td>
              <td>${if (r.sentenceAccuracy) "是" else "否"}</td>
              <td>${"%.0f%%".format(r.vadMetrics.precision * 100)} / ${"%.0f%%".format(r.vadMetrics.recall * 100)}</td>
              <td>${if (r.endToEndLatencyMs >= 0) "${r.endToEndLatencyMs}ms" else "—"}</td>
              <td class="ref">${r.referenceText.ifEmpty { "（无）" }}</td>
              <td class="hyp">${r.hypothesisText.ifEmpty { "（无）" }}</td>
            </tr>
            """.trimIndent()
        }
        outputFile.writeText(
            """
            <!DOCTYPE html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <title>ASR 评估报告</title>
            <style>
              body { font-family: "Microsoft YaHei", sans-serif; margin: 24px; color: #222; }
              h1 { font-size: 20px; } .summary { margin: 12px 0; color: #555; }
              table { border-collapse: collapse; width: 100%; font-size: 13px; }
              th, td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; }
              th { background: #f5f5f5; }
              tr.good td:nth-child(2) { color: #1a7f37; font-weight: bold; }
              tr.warn td:nth-child(2) { color: #b58a00; font-weight: bold; }
              tr.bad td:nth-child(2) { color: #c62828; font-weight: bold; }
              .ref, .hyp { max-width: 260px; }
            </style></head><body>
            <h1>小智 ASR 测试评估报告</h1>
            <div class="summary">场景数：${reports.size} ｜ 综合均分：${"%.1f".format(overall)} / 100 ｜
            生成时间：${java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ')}</div>
            <table>
            <tr><th>场景</th><th>综合评分</th><th>字错误率</th><th>句子准确</th>
            <th>VAD 精确/召回</th><th>端到端延迟</th><th>参考文本</th><th>识别结果</th></tr>
            $rows
            </table></body></html>
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    /** 生成 JSON 格式报告（供 CI 消费） */
    fun generateJsonReport(reports: List<AsrEvaluationReport>, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        // serializeSpecialFloatingPointValues：容忍极端场景产生的 NaN/Infinity，保证报告总能落盘
        val gson = GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create()
        val payload = mapOf(
            "generated_at" to System.currentTimeMillis(),
            "scenario_count" to reports.size,
            "overall_score" to if (reports.isEmpty()) 0f else reports.map { it.overallScore }.average().toFloat(),
            "reports" to reports
        )
        outputFile.writeText(gson.toJson(payload), Charsets.UTF_8)
    }

    // ---------------- 音频源构建 ----------------

    /** 按输入规格构建虚拟音频源 */
    fun createAudioSource(spec: AudioInputSpec): VirtualAudioSource = when (spec) {
        is AudioInputSpec.WavFile ->
            WavFileAudioSource(resolveResource(spec.path))

        is AudioInputSpec.Synthetic ->
            SyntheticAudioSource(spec.pattern, spec.durationMs, spec.baseLevel)

        is AudioInputSpec.ChineseSpeech -> {
            val pcm = ChineseSpeechSynthesizer.synthesizePcm(
                text = spec.text,
                initialSoftness = spec.initialSoftness
            )
            PcmArraySource(pcm, "中文语音：${spec.text}（句首轻声=${spec.initialSoftness}）")
        }

        is AudioInputSpec.Noisy ->
            NoisyAudioSource(createAudioSource(spec.cleanAudio), spec.noiseLevel, spec.noiseType)

        is AudioInputSpec.RealWorld -> {
            val pcm = ChineseSpeechSynthesizer.loadWav(resolveResource(spec.audioFile))
            val startSample = spec.startMs * ChineseSpeechSynthesizer.SAMPLE_RATE / 1000
            val lenSamples = spec.durationMs * ChineseSpeechSynthesizer.SAMPLE_RATE / 1000
            val end = minOf(pcm.size, startSample + lenSamples)
            require(startSample < pcm.size) { "RealWorld 起始位置超出音频长度: ${spec.audioFile}" }
            PcmArraySource(pcm.copyOfRange(startSample, end), "真实音频：${spec.audioFile}")
        }
    }

    /** 解析资源路径（相对资源根目录，缺失时抛出明确错误） */
    fun resolveResource(relativePath: String): File {
        val file = File(resourceRoot, relativePath)
        require(file.exists()) { "测试资源缺失: ${file.absolutePath}（可用 tools/asr 生成或下载）" }
        return file
    }

    private fun drainAllPcm(source: VirtualAudioSource): ShortArray {
        val chunks = mutableListOf<ShortArray>()
        var total = 0
        var f = source.readFrame()
        while (f != null) {
            chunks.add(f); total += f.size
            f = source.readFrame()
        }
        val pcm = ShortArray(total)
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c, 0, pcm, offset, c.size); offset += c.size
        }
        return pcm
    }

    /** 去除首尾静音（预触发缓冲的静音帧不参与的音频对比；阈值约 0.02 电平，与 VAD 口径一致） */
    private fun trimEdges(pcm: ShortArray, threshold: Int = 655): ShortArray {
        var start = 0
        while (start < pcm.size && Math.abs(pcm[start].toInt()) < threshold) start++
        var end = pcm.size
        while (end > start && Math.abs(pcm[end - 1].toInt()) < threshold) end--
        return pcm.copyOfRange(start, end)
    }

    /** PCM 数组音频源（合成/切片的中间载体） */
    private class PcmArraySource(private val pcm: ShortArray, private val desc: String) : VirtualAudioSource {
        private var position = 0
        override fun readFrame(): ShortArray? {
            if (position >= pcm.size) return null
            val size = minOf(FRAME_SIZE, pcm.size - position)
            val frame = pcm.copyOfRange(position, position + size)
            position += size
            return frame
        }
        override fun reset() { position = 0 }
        override val totalFrames: Int get() = (pcm.size + FRAME_SIZE - 1) / FRAME_SIZE
        override val description: String get() = desc
    }

    private fun scoreClass(score: Float) = when {
        score >= 80 -> "good"
        score >= 60 -> "warn"
        else -> "bad"
    }

    companion object {
        const val FRAME_SIZE = 960
        const val FRAME_MS = 60L
        /** 音频输入结束后补的静音帧数（覆盖 5 帧防抖 + 1000ms 静音计时 + 余量） */
        const val TRAILING_SILENCE_FRAMES = 40

        /** 资源根目录探测：优先系统属性，其次常见相对位置 */
        fun defaultResourceRoot(): File {
            val candidates = listOf(
                System.getProperty("asr.resources.dir"),
                "src/test/resources/asr",            // gradle 单测工作目录 = app/
                "app/src/test/resources/asr",        // 项目根目录执行
            ).filterNotNull()
            for (c in candidates) {
                val dir = File(c)
                if (dir.isDirectory) return dir.absoluteFile
            }
            return File("src/test/resources/asr").absoluteFile
        }
    }
}

// ---------------- 评分模型 ----------------

/**
 * 综合评分模型（对应计划 §4 评分标准体系）。
 *
 * 综合评分 = 识别准确率(40%) + VAD 性能(25%) + 延迟表现(20%) + 音频质量(15%)
 */
object ScoreCalculator {

    fun overallScore(report: AsrEvaluationReport): Float {
        val accuracy = accuracyScore(report)      // 40%
        val vad = vadScore(report)                // 25%
        val latency = latencyScore(report)        // 20%
        val quality = qualityScore(report)        // 15%
        return accuracy * 0.40f + vad * 0.25f + latency * 0.20f + quality * 0.15f
    }

    /** 识别准确率 40%：CER 25% + 句子准确率 15% */
    fun accuracyScore(report: AsrEvaluationReport): Float {
        val cerScore = when {
            report.cer <= 0.05f -> 100f
            report.cer <= 0.10f -> 80f
            report.cer <= 0.20f -> 60f
            else -> 0f
        }
        val sentenceScore = if (report.sentenceAccuracy) 100f
        else (1 - report.cer).coerceIn(0f, 1f) * 100f * 0.6f  // 部分正确按比例折算
        // 权重归一：25 + 15 = 40 → 折算为维度内 0..100
        return (cerScore * 25 + sentenceScore * 15) / 40
    }

    /** VAD 性能 25%：精确率 10% + 召回率 10% + 触发延迟 5% */
    fun vadScore(report: AsrEvaluationReport): Float {
        val precisionScore = band(report.vadMetrics.precision, 0.95f, 0.90f, 0.80f)
        val recallScore = band(report.vadMetrics.recall, 0.95f, 0.90f, 0.80f)
        val delayScore = when {
            report.vadTriggerDelayMs < 0 -> 100f  // 无期望触发，不适用
            report.vadTriggerDelayMs <= 200 -> 100f
            report.vadTriggerDelayMs <= 500 -> 80f
            report.vadTriggerDelayMs <= 1000 -> 60f
            else -> 0f
        }
        return (precisionScore * 10 + recallScore * 10 + delayScore * 5) / 25
    }

    /** 延迟表现 20%：端到端延迟 15% + 状态转换延迟 5% */
    fun latencyScore(report: AsrEvaluationReport): Float {
        val e2eScore = when {
            report.endToEndLatencyMs < 0 -> 100f  // 未触发 STT，不适用
            report.endToEndLatencyMs <= 1000 -> 100f
            report.endToEndLatencyMs <= 2000 -> 80f
            report.endToEndLatencyMs <= 3000 -> 60f
            else -> 0f
        }
        val stateDelayScore = when {
            report.vadTriggerDelayMs < 0 -> 100f
            report.vadTriggerDelayMs <= 100 -> 100f
            report.vadTriggerDelayMs <= 300 -> 80f
            else -> 60f
        }
        return (e2eScore * 15 + stateDelayScore * 5) / 20
    }

    /** 音频质量 15%：电平保持度 10% + 频谱失真度 5% */
    fun qualityScore(report: AsrEvaluationReport): Float {
        val preservationScore = band(report.audioQuality.levelPreservation, 0.95f, 0.90f, 0.80f)
        val distortionScore = when {
            report.audioQuality.spectralDistortion <= 0.05f -> 100f
            report.audioQuality.spectralDistortion <= 0.10f -> 80f
            else -> 0f
        }
        return (preservationScore * 10 + distortionScore * 5) / 15
    }

    /** 通用分档：≥excellent 100 分；≥good 80 分；≥acceptable 60 分；否则 0 分 */
    private fun band(value: Float, excellent: Float, good: Float, acceptable: Float): Float = when {
        value >= excellent -> 100f
        value >= good -> 80f
        value >= acceptable -> 60f
        else -> 0f
    }
}

// ---------------- 测试套件 ----------------

/** 预定义测试套件（场景由 suites/ 目录下各套件对象提供） */
enum class TestSuite(val description: String, val scenarios: List<AsrTestScenario>) {
    /** 基础功能测试：验证基本语音交互流程 */
    BASIC_FUNCTIONALITY("基础语音交互功能验证", BasicFunctionalitySuite.scenarios),

    /** VAD 边界测试：验证语音活动检测的准确性 */
    VAD_BOUNDARY("VAD 阈值与防抖机制边界测试", VadBoundarySuite.scenarios),

    /** 句首轻声专项测试：验证中文句首轻声的识别完整性 */
    CHINESE_INITIAL_SOFTNESS("中文句首轻声识别完整性测试", ChineseSoftnessSuite.scenarios),

    /** 噪声环境测试：验证不同噪声环境下的识别能力 */
    NOISE_ROBUSTNESS("噪声环境下的语音识别鲁棒性测试", NoiseRobustnessSuite.scenarios),

    /** 打断场景测试：验证用户打断 AI 说话时的识别能力 */
    INTERRUPTION("用户打断场景的语音识别测试", InterruptionSuite.scenarios),

    /** 真实世界音频测试：使用从开放平台下载的真实语音 */
    REAL_WORLD_SPEECH("真实世界语音识别测试（访谈/播客/新闻）", RealWorldSpeechSuite.scenarios),

    /** 压力测试：验证长时间/高频次交互的稳定性 */
    STRESS("长时间语音交互压力测试", StressSuite.scenarios)
}

/** 测试套件执行报告 */
data class TestSuiteReport(
    val suite: String,
    val description: String,
    val timestamp: Long,
    val reports: List<AsrEvaluationReport>
) {
    val overallScore: Float
        get() = if (reports.isEmpty()) 0f else reports.map { it.overallScore }.average().toFloat()

    val passedCount: Int
        get() = reports.count { it.overallScore >= PASS_THRESHOLD }

    val failedCount: Int
        get() = reports.size - passedCount

    companion object {
        /** 场景通过线：综合评分 ≥ 80 */
        const val PASS_THRESHOLD = 80f
    }
}

// ---------------- 历史趋势对比 ----------------

/**
 * 评估历史归档与趋势对比（对应计划 Phase 4「历史趋势对比 / 基线对比」）。
 *
 * 每次执行以时间戳归档一份 JSON 快照到 `<reportsDir>/history/`；
 * 对比本次与上一次（基线）的均分/通过率/瓶颈数，输出逐场景变化。
 */
object AsrReportHistory {

    /** 单次归档快照 */
    data class RunSnapshot(
        val timestamp: Long,
        val overallScore: Float,
        val passRate: Float,
        val bottleneckCount: Int,
        val scenarioScores: Map<String, Float>
    )

    /** 两次运行的对比结果 */
    data class TrendComparison(
        val baseline: RunSnapshot,
        val current: RunSnapshot,
        val scoreDelta: Float,
        val passRateDelta: Float,
        val bottleneckDelta: Int,
        /** 场景名 → 分数变化（仅含双方都有的场景） */
        val perScenarioDelta: Map<String, Float>,
        /** 评分显著下降（≥5 分）的场景，提示回归 */
        val regressions: List<String>
    ) {
        val isRegression: Boolean get() = scoreDelta < 0 && regressions.isNotEmpty()
    }

    private val gson = GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create()

    /** 归档本次运行快照；返回快照对象 */
    fun record(reports: List<AsrEvaluationReport>, reportsDir: File): RunSnapshot {
        val snapshot = RunSnapshot(
            timestamp = System.currentTimeMillis(),
            overallScore = if (reports.isEmpty()) 0f else reports.map { it.overallScore }.average().toFloat(),
            passRate = if (reports.isEmpty()) 0f else
                reports.count { it.overallScore >= TestSuiteReport.PASS_THRESHOLD }.toFloat() / reports.size,
            bottleneckCount = reports.sumOf { BottleneckAnalyzer.analyze(it).size },
            scenarioScores = reports.associate { it.scenarioName to it.overallScore }
        )
        val historyDir = File(reportsDir, "history").also { it.mkdirs() }
        val file = File(historyDir, "run-${snapshot.timestamp}.json")
        file.writeText(gson.toJson(snapshot), Charsets.UTF_8)
        return snapshot
    }

    /** 列出历史快照（按时间升序） */
    fun listRuns(reportsDir: File): List<RunSnapshot> {
        val historyDir = File(reportsDir, "history")
        if (!historyDir.isDirectory) return emptyList()
        return historyDir.listFiles { f -> f.name.startsWith("run-") && f.extension == "json" }
            ?.sortedBy { it.name }
            ?.mapNotNull { f ->
                try {
                    gson.fromJson(f.readText(Charsets.UTF_8), RunSnapshot::class.java)
                } catch (_: Exception) {
                    null
                }
            } ?: emptyList()
    }

    /** 与上一次（基线）对比；无历史时返回 null */
    fun compareWithBaseline(current: RunSnapshot, reportsDir: File): TrendComparison? {
        val baseline = listRuns(reportsDir).lastOrNull { it.timestamp != current.timestamp } ?: return null
        val deltas = current.scenarioScores.mapNotNull { (name, score) ->
            baseline.scenarioScores[name]?.let { name to (score - it) }
        }.toMap()
        return TrendComparison(
            baseline = baseline,
            current = current,
            scoreDelta = current.overallScore - baseline.overallScore,
            passRateDelta = current.passRate - baseline.passRate,
            bottleneckDelta = current.bottleneckCount - baseline.bottleneckCount,
            perScenarioDelta = deltas,
            regressions = deltas.filter { it.value <= -5f }.keys.toList()
        )
    }
}
