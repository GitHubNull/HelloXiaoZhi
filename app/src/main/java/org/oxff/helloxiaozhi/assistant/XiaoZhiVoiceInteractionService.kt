package org.oxff.helloxiaozhi.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * 小智系统语音助手服务。
 *
 * 注册为系统默认语音助手后，用户可通过长按 Home 键、手势或硬件按键唤醒。
 * 唤醒后由 [XiaoZhiVoiceInteractionSessionService] 创建会话，进而启动语音通话界面。
 *
 * 注意：此类本身不处理语音交互，仅作为系统入口点。
 */
class XiaoZhiVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "VoiceInteractionService onReady")
    }

    override fun onShutdown() {
        Log.i(TAG, "VoiceInteractionService onShutdown")
        super.onShutdown()
    }

    companion object {
        private const val TAG = "XiaoZhiVoiceService"
    }
}
