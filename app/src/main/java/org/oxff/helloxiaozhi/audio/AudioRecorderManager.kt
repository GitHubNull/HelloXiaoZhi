package org.oxff.helloxiaozhi.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import org.oxff.helloxiaozhi.util.AudioMath

/**
 * 麦克风采集管理器，对应 Web 端 AudioWorklet + 后端 AudioProcessor 的
 * "采集 → 成帧（960 采样 / 60ms@16kHz）→ 电平检测"链路。
 *
 * 兼容性设计：
 *  - 首选 16kHz 采集（小智协议标准采样率）；
 *  - 若设备不支持 16kHz（getMinBufferSize 返回异常），降级 44.1kHz 采集 +
 *    线性重采样到 16kHz（对应 AudioService 中 24kHz 采集与后端 16kHz
 *    处理之间的采样率转换思想）。
 *  - 回声消除（AEC）可用时开启，失败静默降级。
 */
class AudioRecorderManager(
    private val onFrame: (frame: ShortArray, level: Float) -> Unit,
    private val onError: (message: String) -> Unit,
) {

    @Volatile
    private var stopped = false

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var frameCount = 0

    /** 开始采集（新线程中执行，不阻塞调用方） */
    fun start() {
        if (thread != null && thread!!.isAlive) return
        stopped = false
        thread = Thread({ recordLoop() }, "audio-record").apply { start() }
    }

    /** 停止采集并等待线程退出（最多 1 秒） */
    fun stop() {
        stopped = true
        record?.let {
            try {
                it.stop()
            } catch (_: Exception) {
                // 已停止或未初始化时忽略
            }
        }
        thread?.join(1000)
        thread = null
        record = null
    }

    private fun recordLoop() {
        try {
            val rate = probeSampleRate()
            if (rate <= 0) {
                onError("当前设备不支持录音（缺少 16000Hz 采样率支持）")
                return
            }
            Log.i(TAG, "record start: sampleRate=$rate (target=$TARGET_SAMPLE_RATE)")
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                onError("录音初始化失败（缓冲区大小无效）")
                return
            }
            // 音频源选择：
            //  - 用 MIC（原始麦克风），对应 Web 端 getUserMedia 的普通采集；
            //  - 不用 VOICE_COMMUNICATION：该模式走通话链路，依赖下行音频作
            //    为 AEC 参考信号。在无通话的普通 App 场景，部分设备（一加/OPPO
            //    等）会把麦克风输入当作回声全部消除，导致采集到全零数据
            //    （实测 level 恒为 0.000，服务器收不到有效语音）。
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, rate / 5),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                onError("录音初始化失败")
                return
            }
            this.record = record
            attachAec(record)
            record.startRecording()

            val resampler = if (rate != TARGET_SAMPLE_RATE) {
                LinearResampler(rate, TARGET_SAMPLE_RATE)
            } else {
                null
            }
            // 每次读取 40ms 的数据，再切分为 60ms 帧
            val readBuffer = ShortArray(rate / 25)
            val frame = ShortArray(OpusCodec.FRAME_SIZE)
            var filled = 0

            while (!stopped) {
                val n = record.read(readBuffer, 0, readBuffer.size)
                if (n <= 0) continue
                val samples = resampler?.push(readBuffer, n) ?: readBuffer
                var idx = 0
                while (idx < samples.size && !stopped) {
                    val copy = minOf(OpusCodec.FRAME_SIZE - filled, samples.size - idx)
                    System.arraycopy(samples, idx, frame, filled, copy)
                    filled += copy
                    idx += copy
                    if (filled == OpusCodec.FRAME_SIZE) {
                        val level = AudioMath.rmsLevel(frame)
                        frameCount++
                        // 前 20 帧全量打印（定位采集是否有效），之后每 100 帧
                        if (frameCount <= 20 || frameCount % 100 == 0) {
                            Log.i(TAG, "record frame #$frameCount level=${"%.3f".format(level)}")
                        }
                        onFrame(frame.copyOf(), level)
                        filled = 0
                    }
                }
            }
            record.stop()
            record.release()
        } catch (e: Exception) {
            onError("录音异常: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 探测可用的采集采样率：优先 16kHz，其次 44.1kHz */
    private fun probeSampleRate(): Int {
        if (AudioRecord.getMinBufferSize(
                TARGET_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ) > 0
        ) {
            return TARGET_SAMPLE_RATE
        }
        if (AudioRecord.getMinBufferSize(
                FALLBACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ) > 0
        ) {
            return FALLBACK_SAMPLE_RATE
        }
        return 0
    }

    /** 尝试启用回声消除（可用时），失败静默降级 */
    private fun attachAec(record: AudioRecord) {
        try {
            if (!AcousticEchoCanceler.isAvailable()) return
            val aec = AcousticEchoCanceler.create(record.audioSessionId)
            if (aec != null) {
                aec.enabled = true
            }
        } catch (_: Exception) {
            // AEC 不可用时静默降级，不影响主流程
        }
    }

    private companion object {
        const val TAG = "AudioRecorder"
        const val TARGET_SAMPLE_RATE = 16000
        const val FALLBACK_SAMPLE_RATE = 44100
    }
}

/**
 * 流式线性重采样器：将任意输入采样率的 PCM 转换到 16kHz。
 * 仅在设备不支持 16kHz 采集的兜底路径上使用。
 */
class LinearResampler(private val fromRate: Int, private val toRate: Int) {

    /** 每输出一个采样需要消费的输入采样数（> 1 表示降采样） */
    private val step = fromRate.toDouble() / toRate

    /** 上次 push 后剩余的输入采样（未消费部分） */
    private var carry = DoubleArray(0)

    /**
     * 推入一段输入采样，返回本次可产出的重采样结果。
     *
     * @param input 输入 PCM（int16）
     * @param length 有效长度（input.size 以内）
     */
    fun push(input: ShortArray, length: Int): ShortArray {
        val data = DoubleArray(carry.size + length) { i ->
            if (i < carry.size) carry[i] else input[i - carry.size].toDouble()
        }
        val output = ArrayList<Short>()
        var srcPos = 0.0
        while (srcPos + 1.0 <= data.size) {
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val i1 = minOf(i0 + 1, data.size - 1)
            val value = data[i0] * (1 - frac) + data[i1] * frac
            output.add(value.coerceIn(-32768.0, 32767.0).toInt().toShort())
            srcPos += step
        }
        // 保存尚未消费的输入（含小数部分对应的基准采样之后的数据）
        val keepFrom = minOf(srcPos.toInt(), data.size)
        carry = if (keepFrom < data.size) {
            data.copyOfRange(keepFrom, data.size)
        } else {
            DoubleArray(0)
        }
        return output.toShortArray()
    }
}
