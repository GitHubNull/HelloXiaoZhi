package org.oxff.helloxiaozhi.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 音乐播放器：基于 MediaPlayer 的播放引擎封装。
 *
 * 功能：
 *  - 播放/暂停/恢复/停止/上一首/下一首
 *  - 播放队列管理
 *  - 音频焦点处理（与 TTS 播放协调）
 *  - 状态回调通知
 *
 * 与 AudioPipeline 的协调：
 *  - 音乐播放时暂停 TTS 播放（通过 [onMusicStart] 回调）
 *  - 音乐停止/暂停时恢复 TTS 播放（通过 [onMusicStop] 回调）
 */
open class MusicPlayer(private val context: Context) {

    /** 当前播放曲目变更回调 */
    var onTrackChanged: ((MusicTrack?) -> Unit)? = null

    /** 播放状态变更回调 */
    var onPlaybackStateChanged: ((PlaybackState) -> Unit)? = null

    /** 错误回调 */
    var onError: ((String) -> Unit)? = null

    /** 音乐开始播放回调（用于暂停 TTS） */
    var onMusicStart: (() -> Unit)? = null

    /** 音乐停止播放回调（用于恢复 TTS） */
    var onMusicStop: (() -> Unit)? = null

    private var mediaPlayer: MediaPlayer? = null
    private var currentTrack: MusicTrack? = null
    private var playlist: List<MusicTrack> = emptyList()
    private var currentIndex: Int = -1

    /** 当前播放状态 */
    @Volatile
    var state: PlaybackState = PlaybackState.IDLE
        private set

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    /** 是否拥有音频焦点 */
    @Volatile
    private var hasAudioFocus = false

    // ---------------- 播放控制 ----------------

    /**
     * 播放指定曲目
     */
    open fun play(track: MusicTrack) {
        Log.i(TAG, "play: ${track.title} - ${track.artist}")
        stopInternal()
        currentTrack = track
        playlist = listOf(track)
        currentIndex = 0
        startPlayback(track)
    }

    /**
     * 播放曲目列表（从指定索引开始）
     */
    fun playPlaylist(tracks: List<MusicTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) {
            Log.w(TAG, "playPlaylist: empty list")
            return
        }
        Log.i(TAG, "playPlaylist: ${tracks.size} tracks, startIndex=$startIndex")
        stopInternal()
        playlist = tracks
        currentIndex = startIndex.coerceIn(0, tracks.size - 1)
        currentTrack = playlist[currentIndex]
        startPlayback(currentTrack!!)
    }

    /**
     * 暂停播放
     */
    open fun pause() {
        if (state != PlaybackState.PLAYING) return
        Log.i(TAG, "pause")
        mediaPlayer?.pause()
        setState(PlaybackState.PAUSED)
        abandonAudioFocus()
        onMusicStop?.invoke()
    }

    /**
     * 恢复播放
     */
    open fun resume() {
        if (state != PlaybackState.PAUSED) return
        Log.i(TAG, "resume")
        if (requestAudioFocus()) {
            mediaPlayer?.start()
            setState(PlaybackState.PLAYING)
            onMusicStart?.invoke()
        }
    }

    /**
     * 停止播放
     */
    open fun stop() {
        Log.i(TAG, "stop")
        stopInternal()
        setState(PlaybackState.IDLE)
        onMusicStop?.invoke()
    }

    /**
     * 下一首
     */
    open fun next(): Boolean {
        if (playlist.isEmpty()) return false
        val nextIndex = (currentIndex + 1) % playlist.size
        Log.i(TAG, "next: $currentIndex -> $nextIndex")
        currentIndex = nextIndex
        currentTrack = playlist[currentIndex]
        startPlayback(currentTrack!!)
        return true
    }

    /**
     * 上一首
     */
    open fun previous(): Boolean {
        if (playlist.isEmpty()) return false
        val prevIndex = if (currentIndex > 0) currentIndex - 1 else playlist.size - 1
        Log.i(TAG, "previous: $currentIndex -> $prevIndex")
        currentIndex = prevIndex
        currentTrack = playlist[currentIndex]
        startPlayback(currentTrack!!)
        return true
    }

    /**
     * 设置音量（0.0 ~ 1.0）
     */
    fun setVolume(volume: Float) {
        mediaPlayer?.setVolume(volume, volume)
    }

    /**
     * 获取当前曲目
     */
    fun getCurrentTrack(): MusicTrack? = currentTrack

    /**
     * 获取当前播放列表
     */
    fun getPlaylist(): List<MusicTrack> = playlist.toList()

    /**
     * 获取当前索引
     */
    fun getCurrentIndex(): Int = currentIndex

    // ---------------- 内部实现 ----------------

    private fun startPlayback(track: MusicTrack) {
        if (!requestAudioFocus()) {
            Log.w(TAG, "Failed to gain audio focus")
            onError?.invoke("无法获取音频焦点")
            return
        }

        try {
            val mp = MediaPlayer()
            mediaPlayer = mp

            // 设置音频属性（音乐播放）
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            // 设置数据源
            when (track.source) {
                MusicSource.LOCAL -> {
                    mp.setDataSource(track.path)
                }
                MusicSource.SAF -> {
                    mp.setDataSource(context, Uri.parse(track.path))
                }
            }

            // 准备完成回调
            mp.setOnPreparedListener { player ->
                Log.i(TAG, "MediaPlayer prepared, starting playback")
                player.start()
                setState(PlaybackState.PLAYING)
                notifyTrackChanged(track)
                onMusicStart?.invoke()
            }

            // 播放完成回调
            mp.setOnCompletionListener {
                Log.i(TAG, "Playback completed")
                // 自动播放下一首
                if (playlist.size > 1) {
                    next()
                } else {
                    setState(PlaybackState.IDLE)
                    onMusicStop?.invoke()
                }
            }

            // 错误回调
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                onError?.invoke("播放错误: $what")
                setState(PlaybackState.ERROR)
                abandonAudioFocus()
                true
            }

            // 异步准备
            mp.prepareAsync()
            setState(PlaybackState.PREPARING)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            onError?.invoke("播放失败: ${e.message}")
            setState(PlaybackState.ERROR)
            abandonAudioFocus()
        }
    }

    private fun stopInternal() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping MediaPlayer", e)
            }
        }
        mediaPlayer = null
        abandonAudioFocus()
    }

    private fun setState(newState: PlaybackState) {
        if (state != newState) {
            state = newState
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onPlaybackStateChanged?.invoke(newState)
            }
        }
    }

    private fun notifyTrackChanged(track: MusicTrack?) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onTrackChanged?.invoke(track)
        }
    }

    // ---------------- 音频焦点 ----------------

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange -> handleAudioFocusChange(focusChange) },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "requestAudioFocus: ${if (hasAudioFocus) "granted" else "denied"}")
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasAudioFocus = false
        Log.i(TAG, "abandonAudioFocus")
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Audio focus lost, stopping playback")
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost transiently, pausing")
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus gained")
                if (state == PlaybackState.PAUSED) {
                    resume()
                }
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopInternal()
        currentTrack = null
        playlist = emptyList()
        currentIndex = -1
    }

    companion object {
        private const val TAG = "MusicPlayer"
    }
}

/** 播放状态 */
enum class PlaybackState {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    ERROR,
}
