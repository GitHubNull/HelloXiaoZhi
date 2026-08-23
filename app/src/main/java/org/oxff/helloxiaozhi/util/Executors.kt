package org.oxff.helloxiaozhi.util

import android.os.Handler

/**
 * UI 线程执行器抽象：状态机等核心逻辑通过它切换到主线程，
 * 便于 JVM 单元测试注入直接执行器。
 */
interface UiExecutor {
    fun post(action: Runnable)
}

/** 生产实现：投递到 Android 主线程 Handler */
class HandlerExecutor(private val handler: Handler) : UiExecutor {
    override fun post(action: Runnable) {
        handler.post(action)
    }
}

/** 测试实现：直接在当前线程执行 */
class DirectExecutor : UiExecutor {
    override fun post(action: Runnable) {
        action.run()
    }
}

/**
 * 延时任务调度器抽象：ChatStateMachine 的用户静音计时依赖它，
 * 生产用 Handler，测试用可控的假实现。
 */
interface SilenceScheduler {
    /** 安排一个延时动作（同一时刻只保留一个任务） */
    fun schedule(delayMs: Long, action: () -> Unit)

    /** 取消已安排的动作（无任务时为空操作） */
    fun cancel()
}

/** 生产实现：基于主线程 Handler 的延时调度 */
class HandlerSilenceScheduler(private val handler: Handler) : SilenceScheduler {

    private var runnable: Runnable? = null

    override fun schedule(delayMs: Long, action: () -> Unit) {
        val r = Runnable {
            runnable = null
            action()
        }
        runnable = r
        handler.postDelayed(r, delayMs)
    }

    override fun cancel() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }
}
