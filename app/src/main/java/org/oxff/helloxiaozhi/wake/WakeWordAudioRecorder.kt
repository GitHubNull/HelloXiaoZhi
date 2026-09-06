package org.oxff.helloxiaozhi.wake

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import org.oxff.helloxiaozhi.util.AudioMath

/**
 * 唤醒词音频采集器：负责音频采集与处理。
 *
 * 职责：
 *  - 管理 AudioRecord 生命周期
 *  - 采集音频数据并送入唤醒词引擎
 *  - 附加噪声抑制器
 *
 * 从 WakeWordService 拆分而来，专注于音频采集职责。
 */
class WakeWordAudioRecorder(
    private val engine: SherpaOnnxWakeWordEngine?,
    private val onLog: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordThread: Thread? = null

    @Volatile
    private var recordActive = false

    /**
     * 启动音频采集
     * @param isRunning 检测运行状态检查函数
     * @return true 表示启动成功，false 表示失败
     */
    fun start(isRunning: () -> Boolean): Boolean {
        val sampleRate = engine?.sampleRate ?: 16000
        // sherpa-onnx KWS 为流式输入，按 100ms 采样块喂入归一化 FloatArray
        val chunkSamples = CHUNK_DURATION_MS * sampleRate / 1000

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufSize <= 0) {
            onLog("AudioRecord 缓冲区大小无效: $minBufSize")
            return false
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufSize * 2, chunkSamples * 2),
        ).also { record ->
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onLog("AudioRecord 初始化失败")
                record.release()
                return false
            }
            record.startRecording()
            attachNoiseSuppressor(record)
        }

        recordActive = true
        recordThread = Thread({
            recordLoop(chunkSamples, isRunning)
        }, "wake-word-record").apply { start() }

        return true
    }

    /**
     * 停止音频采集
     */
    fun stop() {
        // 先置标志让录音线程退出循环，再 join 等待其结束，最后释放 AudioRecord
        recordActive = false
        recordThread?.join(500)
        recordThread = null
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Exception) {
                // 已停止
            }
            it.release()
        }
        audioRecord = null
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    /**
     * 附加噪声抑制器
     */
    private fun attachNoiseSuppressor(record: AudioRecord) {
        try {
            val sessionId = record.audioSessionId
            if (NoiseSuppressor.isAvailable() && sessionId != 0) {
                NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                    noiseSuppressor = this
                }
            }
        } catch (_: Exception) {
            // 设备不支持时静默降级
        }
    }

    /**
     * 录音循环
     */
    private fun recordLoop(chunkSamples: Int, isRunning: () -> Boolean) {
        val buffer = ShortArray(chunkSamples)
        var chunkCount = 0
        while (isRunning() && recordActive) {
            val record = audioRecord ?: break
            val read = try {
                record.read(buffer, 0, chunkSamples)
            } catch (_: Exception) {
                onLog("[wake-record] read 异常，退出线程")
                break
            }
            if (read > 0) {
                chunkCount++
                // 按原始 PCM 直接喂引擎（官方示例即原始信号，保证波形无损）
                if (chunkCount % 30 == 0) {
                    val rmsIn = AudioMath.rmsLevel(buffer)
                    onLog("[wake-record] chunk=$chunkCount read=$read rmsIn=$rmsIn")
                }
                val samples = FloatArray(buffer.size) { buffer[it] / 32768.0f }
                engine?.acceptAudio(samples)
            } else {
                onLog("[wake-record] AudioRecord.read 返回异常: $read")
                if (read == AudioRecord.ERROR_INVALID_OPERATION) break
            }
        }
    }

    companion object {
        private const val TAG = "WakeWordAudioRecorder"
        /** 音频采集块时长（毫秒） */
        private const val CHUNK_DURATION_MS = 100
    }
}
