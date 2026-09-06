package org.oxff.helloxiaozhi.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * 小智语音识别服务。
 *
 * 按 VoiceInteractionService 的标准要求，`voice_interaction_service.xml`
 * 的 `recognitionService` 必须指向一个 [RecognitionService] 实现。
 * 本实现为空壳：真正的端到端语音识别由唤醒词检测 + WebSocket 会话承担，
 * 此处仅用于满足系统对 VoiceInteractionService 元数据的解析校验。
 */
class XiaoZhiRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // 空实现：不介入系统语音识别流程
    }

    override fun onCancel(listener: Callback?) {
        // 空实现
    }

    override fun onStopListening(listener: Callback?) {
        // 空实现
    }
}
