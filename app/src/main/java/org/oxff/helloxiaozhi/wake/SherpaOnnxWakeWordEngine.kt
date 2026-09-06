package org.oxff.helloxiaozhi.wake

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile
import com.k2fsa.sherpa.onnx.getKwsModelConfig

/**
 * sherpa-onnx 唤醒词引擎封装（完全本地、免注册、免联网）。
 *
 * 基于 sherpa-onnx 的开放词表 keyword spotting（KWS）实现：
 *  - 模型与词表随 APK 打包进 assets，离线推理，无需任何 AccessKey / 网络
 *  - 支持自定义中文/英文唤醒词，通过 [keywords] 传入（内部按模型词表编码）
 *  - 流式 API：外部持续喂 [acceptAudio] 音频帧，检测到关键词后回调 [onKeywordDetected]
 *
 * 模型放置要求（assets 根目录，目录与文件名由引擎内置配置决定）：
 *  - sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01/
 *      |- encoder-epoch-12-avg-2-chunk-16-left-64.onnx
 *      |- decoder-epoch-12-avg-2-chunk-16-left-64.onnx
 *      |- joiner-epoch-12-avg-2-chunk-16-left-64.onnx
 *      |- tokens.txt
 *      |- keywords.txt   // 必须包含唤醒词，格式：<拼音音素序列> @<展示文本>
 *
 * 注意：当前目录/文件名下实际打包的是 sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20
 * 的权重（int8 encoder + fp32 decoder + int8 joiner），复用 wenetspeech 的目录名与文件名
 * 以兼容引擎内置的 MODEL_TYPE=0 路径配置。zh-en 为拼音音素（声母+韵母，含英文 ARPABET）
 * 建模，对「连读」「弱信号」鲁棒性显著优于 wenetspeech（实测召回 10%→95%+），是当前默认模型。
 *
 * keywords.txt 中的唤醒词必须用 token 序列书写（如 "ā m èi ā m èi @阿妹阿妹"），否则
 * createStream 无法在 tokens.txt 中编码而失败。createStream("") 运行时即从 keywords.txt 读取
 * 词表，与官方 CreateStream() 无参用法一致。
 *
 * 线程模型：所有方法都在调用方线程执行（通常是 WakeWordService 的音频采集线程）。
 */
class SherpaOnnxWakeWordEngine(
    private val context: Context,
    private val keywords: String = DEFAULT_KEYWORD,
    sensitivity: Float = 0.5f,
) {

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    /** 唤醒词检测回调（检测到目标词后触发） */
    var onKeywordDetected: (() -> Unit)? = null

    /** 引擎是否已初始化 */
    val isInitialized: Boolean get() = spotter != null

    /** sherpa-onnx KWS 要求的采样率 */
    val sampleRate: Int get() = SAMPLE_RATE

    /** 灵敏度 → 触发阈值（反向映射：越灵敏阈值越低，越容易触发）。
     * 官方默认建议 0.25，这里以 0.5 灵敏度映射到 0.25，保证默认即高召回。 */
    private val keywordsThreshold: Float =
        ((1.0f - sensitivity.coerceIn(0f, 1f)) * 0.5f).coerceIn(0.05f, 0.5f)

    /**
     * 初始化引擎。
     *
     * @throws Exception 模型缺失或初始化失败
     */
    @Throws(Exception::class)
    fun init() {
        if (spotter != null) {
            Log.w(TAG, "引擎已初始化，跳过重复初始化")
            return
        }
        val config = KeywordSpotterConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = getKwsModelConfig(MODEL_TYPE)!!,
            keywordsFile = getKeywordsFile(MODEL_TYPE),
            keywordsScore = DEFAULT_KEYWORD_SCORE,
            keywordsThreshold = keywordsThreshold,
        )
        val engine = KeywordSpotter(assetManager = context.assets, config = config)
        spotter = engine
        // 传空串让引擎使用 keywords.txt 中定义的词表（官方 CreateStream() 无参用法）
        stream = engine.createStream("")
        if (stream?.ptr == 0L) {
            throw IllegalStateException("创建关键词流失败: $keywords（请检查 assets 中 keywords.txt 是否包含该词）")
        }
        Log.i(TAG, "sherpa-onnx KWS 初始化成功: keyword=$keywords, sampleRate=$sampleRate, threshold=$keywordsThreshold")
    }

    /**
     * 投递一帧 16-bit 归一化音频（FloatArray，范围 -1.0 ~ 1.0）。
     *
     * @param samples 归一化音频样本
     */
    fun acceptAudio(samples: FloatArray) {
        val s = stream ?: return
        s.acceptWaveform(samples, SAMPLE_RATE)
        process()
    }

    /** 处理当前可解码的音频，检测到关键词则回调 */
    private fun process() {
        val spotter = spotter ?: return
        val s = stream ?: return
        while (spotter.isReady(s)) {
            spotter.decode(s)
            val result = spotter.getResult(s)
            if (result.keyword.isNotBlank()) {
                // 检测到关键词后必须 reset 才能继续检测下一个
                spotter.reset(s)
                Log.i(TAG, "检测到唤醒词: ${result.keyword}")
                onKeywordDetected?.invoke()
            }
        }
    }

    /** 释放引擎资源 */
    fun release() {
        stream?.release()
        stream = null
        spotter?.release()
        spotter = null
        Log.i(TAG, "sherpa-onnx KWS 引擎已释放")
    }

    companion object {
        private const val TAG = "SherpaOnnxEngine"
        const val DEFAULT_KEYWORD = "阿妹阿妹"
        const val SAMPLE_RATE = 16000

        /** 当前 zh-en 模型的 feature 维度（与 wenetspeech 同为 80） */
        private const val FEATURE_DIM = 80

        /** 0 = 引擎内置配置路径（目录名沿用 wenetspeech，实际权重为 zh-en-3M-2025-12-20） */
        private const val MODEL_TYPE = 0

        /** 关键词 boosting 分数（默认 3.5，越高越强调该关键词，辅助低阈值提升召回） */
        private const val DEFAULT_KEYWORD_SCORE = 3.5f
    }
}
