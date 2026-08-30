package org.oxff.helloxiaozhi.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 最小化 WAV 解析器。
 *
 * 用于自定义代理服务器模式：该代理将服务器下行的 Opus 解码为
 * 16kHz / 单声道 / 16bit WAV 后发给客户端，因此二进制帧以 "RIFF" 开头。
 * 官方直连模式下二进制帧为原始 Opus，不会进入此解析器（由
 * XiaoZhiController 按魔数分流）。
 */
object WavParser {

    /** 是否为一帧 WAV 数据（RIFF....WAVE 魔数） */
    fun isWav(data: ByteArray): Boolean =
        data.size >= 12 &&
            data[0] == 'R'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() &&
            data[9] == 'A'.code.toByte() &&
            data[10] == 'V'.code.toByte() &&
            data[11] == 'E'.code.toByte()

    /**
     * 解析 WAV 数据，返回 (采样率, PCM 采样)。
     * 仅支持 16bit 整型 PCM；解析失败返回 null。
     */
    fun parse(data: ByteArray): Pair<Int, ShortArray>? {
        if (!isWav(data)) return null
        val bitsPerSample = readShortLE(data, 34)
        if (bitsPerSample != 16) return null
        val sampleRate = readIntLE(data, 24)

        // 遍历 RIFF 块，定位 data 块
        var offset = 12
        while (offset + 8 <= data.size) {
            val chunkId = String(data, offset, 4, Charsets.US_ASCII)
            val chunkSize = readIntLE(data, offset + 4)
            if (chunkId == "data") {
                val start = offset + 8
                val len = minOf(chunkSize, data.size - start)
                val samples = ByteBuffer.wrap(data, start, len)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .let { buf ->
                        val out = ShortArray(buf.remaining())
                        buf.get(out)
                        out
                    }
                return sampleRate to samples
            }
            offset += 8 + chunkSize + (chunkSize % 2)
        }
        return null
    }

    private fun readIntLE(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
