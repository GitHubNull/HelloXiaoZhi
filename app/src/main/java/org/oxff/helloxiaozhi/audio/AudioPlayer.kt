package org.oxff.helloxiaozhi.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
        synchronized(lock) { lock.notifyAll() }
    }

    /** 恢复播放（对应 Web 端 playAudio：AI_START_SPEAKING 事件触发） */
    fun resumePlayback() {
        Log.i(TAG, "resumePlayback: playing=true, queue=${queue.size}")
        playing = true
        lastWriteAt = System.currentTimeMillis()
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

    @Volatile
    private var lastWriteAt = 0L

    private fun playbackLoop() {
        var t: AudioTrack? = null
        while (active) {
            val chunk = queue.poll()
            if (chunk != null) {
                if (playing) {
                    if (t == null) {
                        // 懒创建：首次需要播放时才占用音频输出资源
                        t = ensureTrack() ?: break
                        try {
                            t.play()
                        } catch (_: Exception) {
                            break
                        }
                    }
                    try {
                        t.write(chunk, 0, chunk.size)
                        lastWriteAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                        break
                    }
                }
                // 非播放阶段直接丢弃（用户正在说话）
            } else {
                // 队列空：超过 500ms 没有新数据则视为播放结束
                if (playing && System.currentTimeMillis() - lastWriteAt > EMPTY_TIMEOUT_MS) {
                    Log.i(TAG, "queue empty > ${EMPTY_TIMEOUT_MS}ms, onQueueEmpty")
                    playing = false
                    onQueueEmpty?.invoke()
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
        // minSdk 21，AudioAttributes/AudioFormat.Builder 可用
        val newTrack = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                0, // session id 由系统分配
            )
        } catch (_: Exception) {
            null
        }
        if (newTrack?.state != AudioTrack.STATE_INITIALIZED) {
            newTrack?.release()
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
        const val EMPTY_TIMEOUT_MS = 500L
        const val POLL_WAIT_MS = 50L
    }
}
