package org.oxff.helloxiaozhi.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import org.oxff.helloxiaozhi.R

/**
 * 提示音播放器（预载短音效，基于 SoundPool）。
 *
 * 音效素材为代码波形合成的 wav（轻快明亮），打包进 res/raw：
 *  - 唤醒成功：wake_success.wav（三音上行琶音 C5-E5-G5）
 *  - AI 回答结束：ai_done.wav（单音高亮 E6）
 *
 * 设计要点：
 *  - SoundPool 预加载音效，低延迟、适合频繁播放的短提示音（AI 结束音每轮触发）
 *  - [init] 在应用启动时调用一次，预载音效；未初始化时播放为 no-op（不崩溃）
 *  - [release] 在应用退出时释放资源
 *
 * 所有方法线程安全（内部 SoundPool.play 即可在线程池安全调用）。
 */
object TonePlayer {

    private const val TAG = "TonePlayer"

    /** 播放音量（0..1） */
    private const val VOLUME = 1.0f

    /** 最大并发流（唤醒 + 结束音可能先后/同时触发） */
    private const val MAX_STREAMS = 2

    private var soundPool: SoundPool? = null
    private var wakeSoundId = 0
    private var aiDoneSoundId = 0

    /**
     * 初始化 SoundPool 并预加载音效。应在应用启动（[org.oxff.helloxiaozhi.XiaoZhiApp.onCreate]）调用。
     * 重复调用安全（已初始化则跳过）。
     */
    fun init(context: Context) {
        if (soundPool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attributes)
            .build()
        wakeSoundId = soundPool!!.load(context, R.raw.wake_success, 1)
        aiDoneSoundId = soundPool!!.load(context, R.raw.ai_done, 1)
        Log.i(TAG, "SoundPool 初始化并预载音效: wake=$wakeSoundId, ai_done=$aiDoneSoundId")
    }

    /** 播放唤醒成功提示音（三音上行琶音） */
    fun playWakeUpTone() {
        play(wakeSoundId, "wake_up")
    }

    /** 播放 AI 回答结束提示音（单音高亮 E6） */
    fun playAiDoneTone() {
        play(aiDoneSoundId, "ai_done")
    }

    /** 释放 SoundPool 资源（应用退出时调用） */
    fun release() {
        soundPool?.release()
        soundPool = null
        wakeSoundId = 0
        aiDoneSoundId = 0
        Log.d(TAG, "SoundPool 已释放")
    }

    // ---------------- 内部实现 ----------------

    private fun play(soundId: Int, tag: String) {
        val pool = soundPool ?: run {
            Log.w(TAG, "TonePlayer 未初始化，跳过播放: $tag")
            return
        }
        if (soundId == 0) return
        pool.play(soundId, VOLUME, VOLUME, 1, 0, 1.0f)
        Log.i(TAG, "播放提示音: $tag")
    }
}
