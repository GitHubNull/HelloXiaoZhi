package org.oxff.helloxiaozhi.wake

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.ui.VoiceCallActivity
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
 *
 * 重构后：作为协调者，将具体职责委托给专门组件：
 *  - WakeWordAudioRecorder: 音频采集与处理
 *  - WakeWordNotificationManager: 通知与前台服务管理
 */
class WakeWordService : Service() {

    private var engine: SherpaOnnxWakeWordEngine? = null
    private var audioRecorder: WakeWordAudioRecorder? = null
    private var notificationManager: WakeWordNotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isPaused = false

    private val app: XiaoZhiApp get() = application as XiaoZhiApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WakeWordService onCreate")
        notificationManager = WakeWordNotificationManager(this)
        notificationManager?.createNotificationChannel()
        // 登记进程内实例，供通话页同步暂停/恢复（避免 startService Intent 异步
        // 排队导致 pause 尚未生效、通话录音就与唤醒录音抢占麦克风）
        instance = this
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
                notificationManager?.startForegroundWithNotification()
                startDetection()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "WakeWordService onDestroy")
        stopDetection()
        releaseWakeLock()
        if (instance === this) instance = null
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
        audioRecorder = WakeWordAudioRecorder(engine) { msg ->
            Log.i(TAG, msg)
        }
        if (!audioRecorder!!.start { isRunning }) {
            Log.e(TAG, "音频采集启动失败")
            stopSelf()
        }
    }

    private fun stopAudioRecord() {
        audioRecorder?.stop()
        audioRecorder = null
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

        const val ACTION_STOP = "org.oxff.helloxiaozhi.wake.STOP"
        const val ACTION_PAUSE = "org.oxff.helloxiaozhi.wake.PAUSE"
        const val ACTION_RESUME = "org.oxff.helloxiaozhi.wake.RESUME"

        /** 进程内服务实例（同进程直调，保证 pause/resume 同步生效） */
        @Volatile
        private var instance: WakeWordService? = null

        /** 启动服务 */
        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止服务 */
        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** 暂停检测（通话期间调用）：同步直调服务实例，确保麦克风立即释放 */
        fun pause(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.pauseDetection()
            } else {
                // 服务未运行（唤醒功能未开启）时无需任何操作；走 Intent 兜底
                val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_PAUSE)
                context.startService(intent)
            }
        }

        /** 恢复检测（通话结束后调用）：同步直调服务实例 */
        fun resume(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.resumeDetection()
            } else {
                val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_RESUME)
                context.startService(intent)
            }
        }
    }
}
