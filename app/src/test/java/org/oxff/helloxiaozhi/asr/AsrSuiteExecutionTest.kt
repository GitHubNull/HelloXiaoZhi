package org.oxff.helloxiaozhi.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 套件级执行测试：运行全部预定义测试套件（含真实音频），
 * 生成 HTML/JSON 评估报告与瓶颈分析，归档历史基线。
 *
 * 说明：本测试不做评分断言（评分是评估输出而非通过门槛），
 * 只验证执行链路完整性——每个场景都能产出可解释的报告。
 */
class AsrSuiteExecutionTest {

    /** 报告输出目录（build 目录内，不入库） */
    private val reportsDir: File = File("build/asr-reports").absoluteFile

    @Test
    fun `全部套件执行并生成报告与瓶颈分析`() {
        val runner = AsrTestRunner(outputDir = reportsDir)
        val allReports = mutableListOf<AsrEvaluationReport>()

        for (suite in TestSuite.entries) {
            val suiteReport = runner.runTestSuite(suite)
            assertEquals("${suite.name} 场景数应与套件定义一致",
                suite.scenarios.size, suiteReport.reports.size)
            allReports.addAll(suiteReport.reports)
        }

        assertTrue("应至少执行 24 个合成场景（实际 ${allReports.size}）",
            allReports.size >= 24)

        // 每个场景都必须产出有效报告（评分 0..100、场景名非空）
        for (r in allReports) {
            assertTrue("场景名非空", r.scenarioName.isNotEmpty())
            assertTrue("评分在合法区间: ${r.scenarioName}=${r.overallScore}",
                r.overallScore in 0f..100f)
        }

        // ---- 报告产物 ----
        reportsDir.mkdirs()
        val html = File(reportsDir, "asr_report.html")
        val json = File(reportsDir, "asr_report.json")
        runner.generateHtmlReport(allReports, html)
        runner.generateJsonReport(allReports, json)
        assertTrue("HTML 报告已生成", html.isFile && html.length() > 0)
        assertTrue("JSON 报告已生成", json.isFile && json.length() > 0)

        // ---- 瓶颈分析 ----
        val bottlenecks = BottleneckAnalyzer.analyzeAll(allReports)
        // 写入文本摘要（诊断结论供人工查阅）
        val summary = buildString {
            appendLine("===== ASR 评估执行摘要 =====")
            appendLine("场景总数: ${allReports.size}")
            appendLine("综合均分: ${"%.1f".format(allReports.map { it.overallScore }.average())}")
            appendLine("达标场景(≥80): ${allReports.count { it.overallScore >= TestSuiteReport.PASS_THRESHOLD }}")
            appendLine()
            if (bottlenecks.isEmpty()) {
                appendLine("未检出性能瓶颈")
            } else {
                appendLine("检出瓶颈 ${bottlenecks.size} 项:")
                for (b in bottlenecks) {
                    appendLine("- [${b.severity}] ${b.category}: ${b.recommendation}")
                    if (b.affectedScenarios.isNotEmpty()) {
                        appendLine("  受影响场景: ${b.affectedScenarios.joinToString("、")}")
                    }
                }
            }
        }
        File(reportsDir, "asr_summary.txt").writeText(summary, Charsets.UTF_8)

        // ---- 历史基线归档与趋势对比 ----
        val snapshot = AsrReportHistory.record(allReports, reportsDir)
        assertNotNull(snapshot)
        // 首次运行可能无基线；有基线时必须可对比
        val trend = AsrReportHistory.compareWithBaseline(snapshot, reportsDir)
        if (trend != null) {
            assertTrue("趋势对比分数差应为有限值", !trend.scoreDelta.isNaN())
        }

        println(summary)
    }

    @Test
    fun `真实音频套件加载并执行`() {
        val cases = TestSuite.REAL_WORLD_SPEECH.scenarios
        if (cases.isEmpty()) {
            println("[提示] 真实音频素材尚未准备（运行 gradlew asrFetchResources 下载），跳过实质断言")
            return
        }
        val runner = AsrTestRunner(outputDir = reportsDir)
        val reports = runner.runScenarios(cases)
        assertEquals(cases.size, reports.size)
        for (r in reports) {
            assertTrue("真实音频场景报告评分合法: ${r.scenarioName}",
                r.overallScore in 0f..100f)
        }
        println("[真实音频] ${reports.size} 个场景执行完成，均分 ${"%.1f".format(reports.map { it.overallScore }.average())}")
    }
}
