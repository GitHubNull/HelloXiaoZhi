package org.oxff.helloxiaozhi.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.ui.MainActivity
import org.oxff.helloxiaozhi.ui.VoiceCallActivity
import org.oxff.helloxiaozhi.util.AudioMath
import org.oxff.helloxiaozhi.util.TonePlayer

/**
 * 常驻唤醒词检测前台服务。
 *
 * 职责：
 *  - 持续采集麦克风音频，送入 sherpa-onnx KWS 引擎检测唤醒词
 *  - 检测到唤醒词后切换到唤醒目标机器人并启动语音通话界面
 *  - 持有 PARTIAL_WAKE_LOCK 防止 CPU 休眠导致检测中断
 *  - 与语音通话互斥：通话期间暂停检测，挂断后恢复
 *
 * 启动方式：
 *  - 应用启动时（XiaoZhiApp.onCreate）若配置开启则自动启动
 *  - 设置页手动开关
 *  - 系统广播（BOOT_COMPLETED 等，可选扩展）
 */
class WakeWordService : Service() {

    private var engine: SherpaOnnxWakeWordEngine? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isPaused = false

    /** 录音线程活跃标志：pause 时置 false 让线程退出循环，与 isRunning 解耦 */
    @Volatile
    private var recordActive = false

    private val app: XiaoZhiApp get() = application as XiaoZhiApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WakeWordService onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "WakeWordService onStartCommand, action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseDetection()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeDetection()
                return START_STICKY
            }
            else -> {
                startForegroundWithNotification()
                startDetection()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "WakeWordService onDestroy")
        stopDetection()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---------------- 检测控制 ----------------

    private fun startDetection() {
        if (isRunning) {
            Log.w(TAG, "检测已在运行中")
            return
        }
        if (!checkAudioPermission()) {
            Log.e(TAG, "缺少录音权限，无法启动唤醒词检测")
            stopSelf()
            return
        }
        acquireWakeLock()
        initEngine()
        startAudioRecord()
        isRunning = true
        Log.i(TAG, "唤醒词检测已启动")
    }

    private fun stopDetection() {
        isRunning = false
        stopAudioRecord()
        engine?.release()
        engine = null
        Log.i(TAG, "唤醒词检测已停止")
    }

    private fun pauseDetection() {
        if (isPaused) return
        isPaused = true
        // 必须真正释放麦克风：通话页会另起 AudioRecord，若此处仅置标志位
        // 而让本服务的 AudioRecord 保持 startRecording 状态，两个录音器会
        // 同时占用麦克风，导致通话侧 read() 返回 ERROR_INVALID_OPERATION(-38)。
        stopAudioRecord()
        Log.i(TAG, "唤醒词检测已暂停（通话中，已释放麦克风）")
    }

    private fun resumeDetection() {
        if (!isPaused) return
        isPaused = false
        // 通话结束后重建 AudioRecord，恢复唤醒词检测
        if (isRunning) {
            startAudioRecord()
        }
        Log.i(TAG, "唤醒词检测已恢复")
    }

    // ---------------- 引擎与音频 ----------------

    private fun initEngine() {
        val config = app.config
        engine = SherpaOnnxWakeWordEngine(
            context = this,
            keywords = SherpaOnnxWakeWordEngine.DEFAULT_KEYWORD,
            sensitivity = config.wakeWordSensitivity,
        ).apply {
            onKeywordDetected = {
                onWakeWordDetected()
            }
            try {
                init()
            } catch (e: Exception) {
                Log.e(TAG, "sherpa-onnx KWS 初始化失败", e)
                stopSelf()
            }
        }
    }

    private fun startAudioRecord() {
        val sampleRate = engine?.sampleRate ?: 16000
        // sherpa-onnx KWS 为流式输入，按 100ms 采样块喂入归一化 FloatArray
        val chunkSamples = CHUNK_DURATION_MS * sampleRate / 1000

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufSize <= 0) {
            Log.e(TAG, "AudioRecord 缓冲区大小无效: $minBufSize")
            stopSelf()
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufSize * 2, chunkSamples * 2),
        ).also { record ->
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                record.release()
                stopSelf()
                return
            }
            record.startRecording()
            // 附加噪声抑制：降低背景噪声，提升次远场信噪比（唤醒场景为近场/次远场）
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

        recordActive = true
        recordThread = Thread({
            val buffer = ShortArray(chunkSamples)
            var chunkCount = 0
            while (isRunning && recordActive) {
                val record = audioRecord ?: break
                val read = try {
                    record.read(buffer, 0, chunkSamples)
                } catch (_: Exception) {
                    Log.e(TAG, "[wake-record] read 异常，退出线程")
                    break
                }
                if (read > 0) {
                    chunkCount++
                    // 按原始 PCM 直接喂引擎（官方示例即原始信号，保证波形无损）
                    if (chunkCount % 30 == 0) {
                        val rmsIn = AudioMath.rmsLevel(buffer)
                        Log.i(TAG, "[wake-record] chunk=$chunkCount read=$read rmsIn=$rmsIn")
                    }
                    val samples = FloatArray(buffer.size) { buffer[it] / 32768.0f }
                    engine?.acceptAudio(samples)
                } else {
                    Log.e(TAG, "[wake-record] AudioRecord.read 返回异常: $read")
                    if (read == AudioRecord.ERROR_INVALID_OPERATION) break
                }
            }
        }, "wake-word-record").apply { start() }
    }

    private fun stopAudioRecord() {
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

    // ---------------- 唤醒处理 ----------------

    private fun onWakeWordDetected() {
        Log.i(TAG, "检测到唤醒词")
        val repository = app.repository
        val controller = app.controller

        // 唤醒成功提示音：作为视觉反馈的补充，确保用户在不看屏幕的情况下也能明确知道设备已唤醒
        if (app.config.wakeSoundEnabled) {
            TonePlayer.playWakeUpTone()
        }

        // 获取唤醒目标机器人
        val targetBot = repository.defaultBot() ?: run {
            Log.e(TAG, "未找到唤醒目标机器人")
            return
        }

        // 若当前激活机器人不是唤醒目标，先切换
        if (controller.activeBotId != targetBot.id) {
            Log.i(TAG, "切换激活机器人: ${controller.activeBotId} -> ${targetBot.id}")
            controller.switchActiveBot(targetBot.id)
        }

        // 启动语音通话界面
        val intent = Intent(this, VoiceCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(VoiceCallActivity.EXTRA_BOT_ID, targetBot.id)
            putExtra(VoiceCallActivity.EXTRA_AUTO_START_CALL, true)
        }
        startActivity(intent)
    }

    // ---------------- 通知与前台服务 ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.wake_notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(getString(R.string.wake_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.wake_notification_stop),
                stopIntent,
            )
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // ---------------- 权限与锁 ----------------

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HelloXiaoZhi::WakeWordWakeLock",
        ).apply {
            acquire(10 * 60 * 1000L) // 10 分钟超时，防止意外持有
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word_detection"
        private const val NOTIFICATION_ID = 1001
        /** 音频采集块时长（毫秒） */
        private const val CHUNK_DURATION_MS = 100

        const val ACTION_STOP = "org.oxff.helloxiaozhi.wake.STOP"
        const val ACTION_PAUSE = "org.oxff.helloxiaozhi.wake.PAUSE"
        const val ACTION_RESUME = "org.oxff.helloxiaozhi.wake.RESUME"

        /** 启动服务 */
        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止服务 */
        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** 暂停检测（通话期间调用） */
        fun pause(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_PAUSE)
            context.startService(intent)
        }

        /** 恢复检测（通话结束后调用） */
        fun resume(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_RESUME)
            context.startService(intent)
        }
    }
}
