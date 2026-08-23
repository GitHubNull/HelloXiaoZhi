package org.oxff.helloxiaozhi.audio

import java.io.Closeable

/**
 * libopus 编解码器的 Kotlin 封装。
 *
 * 对应原生实现：app/src/main/cpp/opus_jni.c
 *
 * 协议约定（与 ref 后端 websocket_proxy.py 及官方协议一致）：
 *  - 上行编码：16kHz / 单声道 / 60ms 帧（960 采样）
 *  - 下行解码：采样率按服务器 hello 响应中的 audio_params.sample_rate
 *    动态创建（官方默认 16000，音乐场景可能为 24000）
 */
class OpusCodec private constructor() : Closeable {

    companion object {
        init {
            System.loadLibrary("opus_jni")
        }

        /** 60ms @ 16kHz 的采样数，对应 hello 消息中的 frame_duration = 60 */
        const val FRAME_SIZE = 960

        /** 创建编码器（上行：麦克风 PCM -> Opus） */
        fun encoder(sampleRate: Int = 16000, channels: Int = 1): OpusCodec =
            OpusCodec().apply { encHandle = nativeCreateEncoder(sampleRate, channels) }

        /** 创建解码器（下行：服务器 Opus -> PCM） */
        fun decoder(sampleRate: Int = 16000, channels: Int = 1): OpusCodec =
            OpusCodec().apply { decHandle = nativeCreateDecoder(sampleRate, channels) }
    }

    private var encHandle = 0L
    private var decHandle = 0L

    val isClosed: Boolean get() = encHandle == 0L && decHandle == 0L

    /**
     * 编码一帧 PCM 为 Opus。
     *
     * @param pcm 16 位 PCM 采样（长度应为 [FRAME_SIZE]）
     * @return Opus 帧数据；编码失败返回 null
     */
    fun encode(pcm: ShortArray): ByteArray? {
        if (encHandle == 0L) return null
        return nativeEncode(encHandle, pcm, pcm.size)
    }

    /**
     * 解码一帧 Opus 为 PCM。
     *
     * @param data Opus 帧数据
     * @param frameSize 期望的每帧采样数（16kHz 下为 [FRAME_SIZE]，24kHz 下为 1440）
     * @return 16 位 PCM 采样；解码失败返回 null
     */
    fun decode(data: ByteArray, frameSize: Int = FRAME_SIZE): ShortArray? {
        if (decHandle == 0L) return null
        return nativeDecode(decHandle, data, frameSize)
    }

    override fun close() {
        if (encHandle != 0L) {
            nativeDestroyEncoder(encHandle)
            encHandle = 0L
        }
        if (decHandle != 0L) {
            nativeDestroyDecoder(decHandle)
            decHandle = 0L
        }
    }

    private external fun nativeCreateEncoder(sampleRate: Int, channels: Int): Long
    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, frameSize: Int): ByteArray?
    private external fun nativeDecode(handle: Long, data: ByteArray, frameSize: Int): ShortArray?
    private external fun nativeDestroyEncoder(handle: Long)
    private external fun nativeDestroyDecoder(handle: Long)
}
