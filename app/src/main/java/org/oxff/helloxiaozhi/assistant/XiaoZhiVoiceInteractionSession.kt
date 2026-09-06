package org.oxff.helloxiaozhi.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import org.oxff.helloxiaozhi.XiaoZhiApp
import org.oxff.helloxiaozhi.ui.VoiceCallActivity

/**
 * 小智语音交互会话。
 *
 * 系统唤醒语音助手后，此会话被创建并显示。
 * 当前实现直接启动全屏语音通话界面（VoiceCallActivity），
 * 而非显示自定义 Assistant UI（简化方案，体验更一致）。
 */
class XiaoZhiVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "onShow: showFlags=$showFlags")

        val app = context.applicationContext as XiaoZhiApp
        val repository = app.repository
        val controller = app.controller

        // 获取唤醒目标机器人
        val targetBot = repository.defaultBot() ?: run {
            Log.e(TAG, "未找到唤醒目标机器人")
            finish()
            return
        }

        // 若当前激活机器人不是唤醒目标，先切换
        if (controller.activeBotId != targetBot.id) {
            Log.i(TAG, "切换激活机器人: ${controller.activeBotId} -> ${targetBot.id}")
            controller.switchActiveBot(targetBot.id)
        }

        // 启动语音通话界面
        val intent = Intent(context, VoiceCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(VoiceCallActivity.EXTRA_BOT_ID, targetBot.id)
            putExtra(VoiceCallActivity.EXTRA_AUTO_START_CALL, true)
        }
        context.startActivity(intent)

        // 会话已完成使命，直接结束
        finish()
    }

    override fun onHide() {
        super.onHide()
        Log.i(TAG, "onHide")
    }

    companion object {
        private const val TAG = "XiaoZhiSession"
    }
}
