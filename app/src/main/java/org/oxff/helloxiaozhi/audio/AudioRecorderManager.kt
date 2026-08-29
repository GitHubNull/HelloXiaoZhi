package org.oxff.helloxiaozhi.audio

import android.media.AudioFormat
import android.media.AudioManager
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
    private val audioManager: AudioManager? = null,
) {

    @Volatile
    private var stopped = false

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var frameCount = 0
    private var useVoiceCommunication = true
    private var zeroLevelCount = 0

    /**
     * 上行音频的用户增益（dB，相对基准），由通话页「你的增益」滑块设置。
     *
     * 只作用于上行的音频帧，**不**作用于送给状态机的电平——电平用固定的
     * [VAD_REFERENCE_GAIN_DB] 基准。若把用户增益同时作用于电平，滑到高位会让
     * 呼吸声越过说话阈值导致状态机反复模态切换，滑到 0 会让电平恒为 0、
     * 每 9 秒触发一次 VOICE_COMMUNICATION→MIC 的破坏性重建。
     */
    @Volatile
    var micGainDb = 0f

    /** 上行增益的线性系数 */
    private val micGainFactor: Float
        get() = Math.pow(10.0, micGainDb / 20.0).toFloat()

    /** VAD 电平的固定基准增益（线性），与现有阈值调优所基于的 +12dB 一致 */
    private val vadGainFactor: Float
        get() = Math.pow(10.0, VAD_REFERENCE_GAIN_DB / 20.0).toFloat()

    /** 开始采集（新线程中执行，不阻塞调用方） */
    fun start() {
        if (thread != null && thread!!.isAlive) return
        stopped = false
        setCommunicationMode()
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
        resetAudioMode()
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
            //  - VOICE_COMMUNICATION：走通话链路，启用硬件 AEC（回声消除）。
            //    需要配合 AudioManager.MODE_IN_COMMUNICATION 使用。
            //    风险：部分设备（一加/OPPO）在无下行音频时可能将输入当回声消除，
            //    导致电平恒为 0。但本应用有下行音频（AI 语音），AEC 有参考信号。
            //  - 兜底：如果 VOICE_COMMUNICATION 模式下电平恒为 0，自动降级回 MIC。
            val audioSource = if (useVoiceCommunication) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            val record = AudioRecord(
                audioSource,
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
            // VOICE_COMMUNICATION 模式已内置 AEC，无需额外 attach
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
                        // 电平与上行解耦：电平用固定 VAD 基准增益（用户不可调，
                        // 保证 VAD 阈值与零电平降级检测不受滑块影响），
                        // 上行帧用用户增益（通话页「你的增益」滑块）
                        val level = AudioMath.rmsLevel(frame) * vadGainFactor
                        val gainedFrame = applyMicGain(frame)
                        frameCount++
                        // VOICE_COMMUNICATION 兜底检测：连续 150 帧电平为 0，降级回 MIC
                        // 注意：AI 播放期间 AEC 可能将输入当回声消除，导致电平为 0
                        // 因此需要更长的检测窗口（150帧≈9秒），避免误触发
                        if (useVoiceCommunication && level < 0.001f) {
                            zeroLevelCount++
                            // 每 10 帧打印一次零电平计数，便于诊断
                            if (zeroLevelCount % 10 == 0) {
                                Log.w(TAG, "zero level count: $zeroLevelCount/150")
                            }
                            if (zeroLevelCount >= 150) {
                                Log.w(TAG, "VOICE_COMMUNICATION 电平恒为 0，降级回 MIC 模式")
                                useVoiceCommunication = false
                                zeroLevelCount = 0
                                // 停止当前录音并立即用 MIC 模式重启：
                                // 若仅 return 不重启，上行音频将永久中断，
                                // 服务器因收不到语音而停止响应（表现为 3-4 句后无响应）
                                record.stop()
                                record.release()
                                this.record = null
                                restartWithMic()
                                return
                            }
                        } else {
                            if (zeroLevelCount > 0) {
                                Log.i(TAG, "zero level reset: was $zeroLevelCount")
                            }
                            zeroLevelCount = 0
                        }
                        // 前 20 帧全量打印（定位采集是否有效），之后每 100 帧
                        // 电平 > 0.01 时也打印（观察用户语音和回声）
                        if (frameCount <= 20 || frameCount % 100 == 0 || level > 0.01f) {
                            Log.i(TAG, "record frame #$frameCount level=${"%.3f".format(level)}")
                        }
                        onFrame(gainedFrame, level)
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

    /**
     * 降级到 MIC 模式后重启录音线程。
     *
     * 在录音线程内部调用（recordLoop return 前）：当前线程即将退出，
     * 必须新起线程重新执行 recordLoop，否则上行音频永久中断。
     */
    private fun restartWithMic() {
        stopped = false
        thread = Thread({ recordLoop() }, "audio-record").apply { start() }
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

    /** 设置通信模式（VOICE_COMMUNICATION 需要配合 MODE_IN_COMMUNICATION） */
    private fun setCommunicationMode() {
        try {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            // 通话模式默认路由到听筒（earpiece），必须强制扬声器输出，
            // 否则用户拿手机远离耳朵时听不到 AI 声音
            audioManager?.isSpeakerphoneOn = true
        } catch (_: Exception) {
            // 设置失败静默降级
        }
    }

    /** 恢复正常音频模式 */
    private fun resetAudioMode() {
        try {
            audioManager?.isSpeakerphoneOn = false
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
            // 设置失败静默降级
        }
    }

    /**
     * 对上行 PCM 帧应用用户增益。
     *
     * 增益公式：gain = 10^(micGainDb/20)，采样值乘以增益后截断到 Int16 范围。
     * 参考 WebRTC AGC：在发送端动态调整增益，确保输出电平稳定。
     */
    private fun applyMicGain(frame: ShortArray): ShortArray {
        if (micGainDb == 0f) return frame.copyOf()
        val gain = micGainFactor
        return ShortArray(frame.size) { i ->
            (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private companion object {
        const val TAG = "AudioRecorder"
        const val TARGET_SAMPLE_RATE = 16000
        const val FALLBACK_SAMPLE_RATE = 44100

        /**
         * VAD 电平的固定基准增益（dB），用户不可调。
         *
         * 取值依据：现有 VAD 阈值（THRESHOLD_SPEAKING=0.02 等）是在 +12dB
         * 基准上调优的；MIC 源安静环境底噪约 0.001，+12dB（约 4 倍线性增益）
         * 即可将正常语音电平（0.01~0.1）抬至阈值以上。保持该基准不变，
         * 用户增益滑块在任何位置 VAD 行为都与调优时一致。
         */
        const val VAD_REFERENCE_GAIN_DB = 12f
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
