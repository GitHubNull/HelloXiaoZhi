package org.oxff.helloxiaozhi.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * 小智语音交互会话服务。
 *
 * 当系统唤醒语音助手时，由系统调用 [onNewSession] 创建新的交互会话。
 */
class XiaoZhiVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i(TAG, "onNewSession")
        return XiaoZhiVoiceInteractionSession(this)
    }

    companion object {
        private const val TAG = "XiaoZhiSessionService"
    }
}
