package org.oxff.helloxiaozhi.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * TTS 音频播放器，对应 Web 端 AudioService 的播放队列。
 *
 * 设计：
 *  - 服务器 Opus 帧解码后的 PCM（int16）进入队列；
 *  - 单一播放线程持续 drain 队列写入 AudioTrack（MODE_STREAM）；
 *  - 播放线程随语音通话会话创建/销毁（startVoiceCall/stopVoiceCall）；
 *  - 用户开口打断时 pausePlayback() 清空队列（对应 Web 端 stopPlaying +
 *    clearAudioQueue），AI 开始说话时 resumePlayback() 恢复（对应 playAudio）；
 *  - 队列自然播空超过 500ms 触发 onQueueEmpty（对应 Web 端 onQueueEmpty）。
 */
class AudioPlayer {

    /** 队列播空回调（用于状态机回退到 IDLE）；在播放线程触发 */
    var onQueueEmpty: (() -> Unit)? = null

    /**
     * AI 播放音量（线性倍数，1.0 为原始音量）。
     *
     * 不能用 AudioTrack.setVolume：它封顶 1.0 且本类用 USAGE_VOICE_COMMUNICATION，
     * 走系统音量会全局改动通话音量。软件乘法在写入前对 PCM 做缩放，可超过 1.0。
     */
    @Volatile
    var playbackGain = 1.0f

    private val queue = ConcurrentLinkedQueue<ShortArray>()
    private val lock = Object()

    @Volatile
    private var sampleRate = DEFAULT_SAMPLE_RATE

    @Volatile
    private var active = false

    /** 当前是否处于播放阶段（AI_SPEAKING） */
    @Volatile
    private var playing = false

    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /**
     * 设置播放采样率（取服务器 hello 响应 audio_params.sample_rate，
     * 默认 16000，音乐场景可能为 24000）。必须在播放线程启动前调用。
     */
    fun setSampleRate(rate: Int) {
        if (rate in 8000..48000) sampleRate = rate
    }

    /** 开始播放会话（进入语音通话时调用，创建 AudioTrack 与播放线程） */
    fun startSession() {
        if (active) return
        active = true
        thread = Thread({ playbackLoop() }, "audio-player").apply { start() }
    }

    /** 结束播放会话（退出语音通话时调用，释放资源） */
    fun stopSession() {
        active = false
        playing = false
        queue.clear()
        synchronized(lock) { lock.notifyAll() }
        thread?.join(1000)
        thread = null
        releaseTrack()
    }

    /** 入队一帧 PCM，等待播放 */
    fun enqueue(pcm: ShortArray) {
        if (!active || pcm.isEmpty()) return
        queue.add(pcm)
        enqueueCount++
        val size = queue.size
        // 诊断：每 50 帧或队列堆积时打印；每帧都打日志会拖慢 WS 接收线程
        if (enqueueCount % 50 == 0 || size >= 3) {
            Log.i(TAG, "enqueue: #$enqueueCount, queue=$size, pcm=${pcm.size} samples")
        }
        synchronized(lock) { lock.notifyAll() }
    }

    /** 恢复播放（对应 Web 端 playAudio：AI_START_SPEAKING 事件触发） */
    fun resumePlayback() {
        Log.i(TAG, "resumePlayback: playing=true, queue=${queue.size}")
        playing = true
        lastWriteAt = System.currentTimeMillis()
        // pausePlayback() 已调用 track.pause()；必须显式 play() 恢复，
        // 否则播放线程写入 paused track 的数据被缓冲但不出声，
        // 表现为「只有第一轮 AI 回答有声音，之后一直静音」
        track?.let {
            try {
                it.play()
            } catch (_: Exception) {
                // 状态异常：交由播放线程在 write 失败时重建 track
            }
        }
        synchronized(lock) { lock.notifyAll() }
    }

    /** 暂停并清空队列（对应 Web 端 stopPlaying + clearAudioQueue：用户开口时触发） */
    fun pausePlayback() {
        Log.i(TAG, "pausePlayback: playing=false, queue=${queue.size}")
        playing = false
        queue.clear()
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: Exception) {
                // AudioTrack 状态异常时静默忽略，播放线程会自行重建
            }
        }
        synchronized(lock) { lock.notifyAll() }
    }

    /** 播放队列是否已空（供 Controller 在 tts stop 时判断可否提前回 IDLE） */
    fun isQueueEmpty(): Boolean = queue.isEmpty()

    @Volatile
    private var lastWriteAt = 0L

    /** 入队帧计数（诊断用，不重置） */
    private var enqueueCount = 0

    private fun playbackLoop() {
        var t: AudioTrack? = null
        while (active) {
            if (!playing) {
                // 非播放阶段：等待状态变更（不取出帧，让帧在队列中缓存）
                synchronized(lock) {
                    if (active) lock.wait(POLL_WAIT_MS)
                }
                continue
            }
            if (t == null) {
                // 懒创建：首次需要播放时才占用音频输出资源
                t = ensureTrack() ?: break
            }
            // 先 peek 不 poll：写入成功才移除，失败时可原帧重试
            val chunk = queue.peek()
            if (chunk != null) {
                if (writeChunkBlocking(t, chunk)) {
                    queue.poll()
                    lastWriteAt = System.currentTimeMillis()
                    // 每 10 帧打印一次播放进度
                    if (queue.size % 10 == 0) {
                        Log.i(TAG, "playback: queue=${queue.size}")
                    }
                } else {
                    // AudioTrack 已死（underrun 禁用或非法状态）：重建后重试同一帧
                    Log.w(TAG, "AudioTrack dead, recreate (queue=${queue.size})")
                    t = recreateTrack(t)
                    if (t == null) break
                }
            } else {
                // 队列空：超过超时时间没有新数据则视为播放结束
                if (playing && System.currentTimeMillis() - lastWriteAt > EMPTY_TIMEOUT_MS) {
                    Log.i(TAG, "queue empty > ${EMPTY_TIMEOUT_MS}ms, onQueueEmpty")
                    playing = false
                    onQueueEmpty?.invoke()
                } else if (playing && queue.isEmpty()) {
                    // 播放中但队列空：记录欠载预警
                    val idleMs = System.currentTimeMillis() - lastWriteAt
                    if (idleMs > 100 && idleMs % 500 < POLL_WAIT_MS) {
                        Log.w(TAG, "queue underrun: idle=${idleMs}ms")
                    }
                }
                synchronized(lock) {
                    if (active) lock.wait(POLL_WAIT_MS)
                }
            }
        }
        try {
            t?.pause()
            t?.flush()
        } catch (_: Exception) {
            // 忽略
        }
    }

    /**
     * 阻塞写入一帧 PCM；返回 false 表示 AudioTrack 已死亡需要重建。
     *
     * 参考官方 ESP32 实现（application.cc OutputAudio）：收到音频帧立即
     * 解码并写入输出设备，不等待攒 buffer。服务器逐句发送音频（gap 5-7s），
     * 若等攒够 jitter buffer 再播放会导致严重延迟。
     *
     * 阻塞式 write 在 AudioTrack 因 underrun 被系统禁用后可能抛出异常，
     * 由调用方重建 track。
     *
     * 播放增益写入新数组而非原地缩放：playbackLoop 是 peek 后写、失败则
     * recreateTrack 后重写同一数组，且 enqueue 按引用存入调用方数组——
     * 原地缩放会在重试时重复施加增益并污染其它持有者。
     */
    private fun writeChunkBlocking(t: AudioTrack, chunk: ShortArray): Boolean {
        val gain = playbackGain
        val out = if (gain == 1.0f) {
            chunk
        } else {
            ShortArray(chunk.size) { i ->
                (chunk[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return try {
            t.write(out, 0, out.size)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 释放死亡的 AudioTrack 并重建 */
    private fun recreateTrack(dead: AudioTrack?): AudioTrack? {
        try {
            dead?.release()
        } catch (_: Exception) {
            // 忽略
        }
        track = null
        return ensureTrack()
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it }
        releaseTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val bufferSize = maxOf(minBuf * 2, sampleRate * 4)
        val attributes = AudioAttributes.Builder()
            // 通话通路：与录音端 VOICE_COMMUNICATION + MODE_IN_COMMUNICATION 配合，
            // 硬件 AEC 才能拿到下行参考信号消除回声；MEDIA 通路会导致 AEC 失效
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val newTrack = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    // 放弃低延迟 fast track：实测部分设备 fast track 实际缓冲远小于
                    // 请求值（2 秒缓冲 0.6 秒即 underrun），写入稍有抖动声音就断续；
                    // 普通通路下请求缓冲才真实生效
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                    .build()
            } else {
                // minSdk 21，AudioAttributes/AudioFormat.Builder 可用
                AudioTrack(attributes, format, bufferSize, AudioTrack.MODE_STREAM, 0)
            }
        } catch (_: Exception) {
            null
        }
        if (newTrack?.state != AudioTrack.STATE_INITIALIZED) {
            newTrack?.release()
            return null
        }
        // MODE_STREAM 必须显式 start 才会消耗写入的数据；否则 write 阻塞但无声
        try {
            newTrack.play()
            Log.i(TAG, "AudioTrack started: sampleRate=$sampleRate, buffer=$bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack play failed", e)
            newTrack.release()
            return null
        }
        track = newTrack
        return newTrack
    }

    private fun releaseTrack() {
        track?.let {
            try {
                it.release()
            } catch (_: Exception) {
                // 忽略
            }
        }
        track = null
    }

    private companion object {
        const val TAG = "AudioPlayer"
        const val DEFAULT_SAMPLE_RATE = 16000
        const val EMPTY_TIMEOUT_MS = 8000L
        const val POLL_WAIT_MS = 40L
    }
}
