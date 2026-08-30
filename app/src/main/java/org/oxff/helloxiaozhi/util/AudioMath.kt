package org.oxff.helloxiaozhi.util

/**
 * 音频信号计算工具。
 */
object AudioMath {

    /**
     * 计算一帧 PCM 的平均电平（0..1 浮点值）。
     *
     * 算法：sum(abs(sample)) / length；此处输入为 int16，
     * 故再除以 32768 归一化。
     */
    fun rmsLevel(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0L
        for (s in frame) {
            sum += if (s < 0) -s.toInt() else s.toInt()
        }
        return sum.toFloat() / frame.size / 32768f
    }
}
