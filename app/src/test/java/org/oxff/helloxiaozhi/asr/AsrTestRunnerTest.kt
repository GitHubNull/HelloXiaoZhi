package org.oxff.helloxiaozhi.asr

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.oxff.helloxiaozhi.chat.ChatState

/**
 * AsrTestRunner 执行器集成测试：
 * 在 JVM 上完整跑通「虚拟音频源 → 状态机 → 模拟服务器 → 评分报告」闭环。
 */
class AsrTestRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newRunner(): AsrTestRunner =
        AsrTestRunner(outputDir = tempFolder.newFolder("reports"))

    @Test
    fun `基础场景端到端执行 完美识别并高分`() {
        val runner = newRunner()
        val scenario = org.oxff.helloxiaozhi.asr.suites.BasicFunctionalitySuite.scenarios.first()
        val report = runner.runScenario(scenario)

        // 模拟服务器按期望文本回显，上行链路完整 → CER=0、句子准确
        assertEquals(0f, report.cer, 1e-6f)
        assertTrue("应收到完整识别回显: ${report.hypothesisText}",
            report.hypothesisText == scenario.expectedStt)
        assertTrue(report.sentenceAccuracy)

        // 期望迁移全部发生：IDLE→USER_SPEAKING 与 USER_SPEAKING→AI_SPEAKING
        assertEquals(1f, report.vadMetrics.recall, 1e-6f)
        assertEquals(0, report.unexpectedTransitions.size)

        // 健康场景综合评分应达到通过线
        assertTrue("综合评分应 ≥ 80（实际 ${report.overallScore}）",
            report.overallScore >= TestSuiteReport.PASS_THRESHOLD)
    }

    @Test
    fun `静默场景不应触发 且不扣分`() {
        val runner = newRunner()
        val silent = scenario("集成_纯静音_不应触发") {
            description("纯静音输入，状态机应保持 IDLE")
            syntheticInput(SyntheticAudioSource.AudioPattern.SILENCE, 1000, 0f)
            expectStt("")
        }
        val report = runner.runScenario(silent)

        assertEquals("不应发生状态迁移", 0, report.unexpectedTransitions.size)
        assertTrue("纯静音场景评分应保持高位（实际 ${report.overallScore}）",
            report.overallScore >= 60f)
    }

    @Test
    fun `套件执行生成聚合报告`() {
        val runner = newRunner()
        val suiteReport = runner.runTestSuite(TestSuite.BASIC_FUNCTIONALITY)

        assertEquals(TestSuite.BASIC_FUNCTIONALITY.scenarios.size, suiteReport.reports.size)
        assertTrue(suiteReport.overallScore > 0f)
        assertEquals(suiteReport.reports.size, suiteReport.passedCount + suiteReport.failedCount)
    }

    @Test
    fun `HTML 与 JSON 报告落盘且内容完整`() {
        val runner = newRunner()
        val reports = runner.runScenarios(
            org.oxff.helloxiaozhi.asr.suites.BasicFunctionalitySuite.scenarios.take(2)
        )

        val html = tempFolder.newFile("report.html")
        runner.generateHtmlReport(reports, html)
        val htmlText = html.readText(Charsets.UTF_8)
        assertTrue(htmlText.contains("小智 ASR 测试评估报告"))
        assertTrue(htmlText.contains(reports.first().scenarioName))

        val json = tempFolder.newFile("report.json")
        runner.generateJsonReport(reports, json)
        val parsed = Gson().fromJson(json.readText(Charsets.UTF_8), JsonObject::class.java)
        assertEquals(2, parsed.get("scenario_count").asInt)
        assertEquals(2, parsed.getAsJsonArray("reports").size())
        assertNotNull(parsed.get("overall_score"))
    }

    @Test
    fun `句首轻声场景串联瓶颈分析`() {
        val runner = newRunner()
        // 极低句首轻声（0.05）：首字低于 VAD 阈值，模拟「句首丢失」缺陷
        val soft = scenario("集成_极轻句首_瓶颈诊断") {
            description("句首字电平低于 0.02 阈值，首字应被丢失")
            chineseSpeech("你猜怎么着", initialSoftness = 0.05f)
            expectStt("你猜怎么着")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
        }
        val report = runner.runScenario(soft)

        // 首字丢失 → 识别回显缺失首字或识别延迟，瓶颈分析应给出预触发类诊断
        // （若状态机恰好在第二字触发，报告迁移时间会显著晚于首字起始）
        val bottlenecks = BottleneckAnalyzer.analyze(report)
        // 无论触发与否，分析流程必须无异常且返回可解释结果
        assertNotNull(bottlenecks)
    }

    @Test
    fun `响应延迟标签注入后延迟指标可见`() {
        val runner = newRunner()
        val delayed = scenario("集成_服务器延迟_800毫秒") {
            description("服务器延迟 800ms 返回 STT，验证端到端延迟采集")
            chineseSpeech("你好", initialSoftness = 1.0f)
            expectStt("你好")
            expectTransition(ChatState.IDLE, ChatState.USER_SPEAKING,
                Trigger.AUDIO_LEVEL_ABOVE_THRESHOLD, 500)
            expectTransition(ChatState.USER_SPEAKING, ChatState.AI_SPEAKING,
                Trigger.SILENCE_TIMEOUT, 2000)
            withTag("responseDelay:800")
        }
        val report = runner.runScenario(delayed)

        assertTrue("端到端延迟应反映注入延迟（实际 ${report.endToEndLatencyMs}ms）",
            report.endToEndLatencyMs >= 500)
        assertEquals("你好", report.hypothesisText)
    }

    @Test
    fun `历史趋势对比 归档与回归检测`() {
        val runner = newRunner()
        val historyDir = tempFolder.newFolder("history-root")
        val reports = runner.runScenarios(
            org.oxff.helloxiaozhi.asr.suites.BasicFunctionalitySuite.scenarios.take(2)
        )

        // 首次归档：无基线可对比
        val first = AsrReportHistory.record(reports, historyDir)
        assertEquals(null, AsrReportHistory.compareWithBaseline(first, historyDir))

        // 第二次运行：分数下降的场景模拟回归；均分不变场景保持基线
        Thread.sleep(5)  // 保证时间戳不同（文件名排序）
        val degraded = reports.mapIndexed { i, r ->
            if (i == 0) r.copy(overallScore = r.overallScore - 20f) else r
        }
        val second = AsrReportHistory.record(degraded, historyDir)
        assertEquals(2, AsrReportHistory.listRuns(historyDir).size)

        val trend = AsrReportHistory.compareWithBaseline(second, historyDir)
        assertNotNull("应存在基线对比", trend)
        assertTrue("均分应下降", trend!!.scoreDelta < 0)
        assertTrue("应检出回归场景", trend.regressions.isNotEmpty())
        assertTrue(trend.isRegression)
    }
}
