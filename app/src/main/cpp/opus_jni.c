/*
 * HelloXiaoZhi - libopus JNI 封装
 *
 * 职责：
 *  - 上行：麦克风 PCM（16kHz / 单声道 / int16）-> Opus 编码
 *  - 下行：服务器 Opus 帧 -> PCM（int16）解码播放
 *
 * 对应 Java 类：org.oxff.helloxiaozhi.audio.OpusCodec
 * 帧长约定：60ms @ 16kHz = 960 采样；服务器可能下发 24kHz（60ms = 1440 采样）
 */
#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define MAX_FRAME_SAMPLES 5760 /* 24kHz 下 60ms 帧的采样数上限（留足余量） */

static OpusEncoder *as_encoder(jlong handle) {
    return (OpusEncoder *) (intptr_t) handle;
}

static OpusDecoder *as_decoder(jlong handle) {
    return (OpusDecoder *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_org_oxff_helloxiaozhi_audio_OpusCodec_nativeCreateEncoder(
        JNIEnv *env, jobject thiz, jint sample_rate, jint channels) {
    int err = OPUS_OK;
    OpusEncoder *enc = opus_encoder_create((opus_int32) sample_rate, channels,
                                           OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == NULL) {
        return 0;
    }
    /* 语音场景：16kbps 码率，VBR 开，适合 16kHz 单声道通话 */
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(16000));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    return (jlong) (intptr_t) enc;
}

JNIEXPORT jbyteArray JNICALL
Java_org_oxff_helloxiaozhi_audio_OpusCodec_nativeEncode(
        JNIEnv *env, jobject thiz, jlong handle, jshortArray pcm,
        jint frame_size) {
    OpusEncoder *enc = as_encoder(handle);
    if (enc == NULL) {
        return NULL;
    }
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (samples == NULL) {
        return NULL;
    }
    unsigned char out[4000];
    opus_int32 len = opus_encode(enc, samples, frame_size, out, sizeof(out));
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    if (len < 0) {
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, len);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, len, (const jbyte *) out);
    }
    return result;
}

JNIEXPORT jlong JNICALL
Java_org_oxff_helloxiaozhi_audio_OpusCodec_nativeCreateDecoder(
        JNIEnv *env, jobject thiz, jint sample_rate, jint channels) {
    int err = OPUS_OK;
    OpusDecoder *dec = opus_decoder_create((opus_int32) sample_rate, channels,
                                           &err);
    if (err != OPUS_OK || dec == NULL) {
        return 0;
    }
    return (jlong) (intptr_t) dec;
}

JNIEXPORT jshortArray JNICALL
Java_org_oxff_helloxiaozhi_audio_OpusCodec_nativeDecode(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray data,
        jint frame_size) {
    OpusDecoder *dec = as_decoder(handle);
    if (dec == NULL) {
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) {
        return NULL;
    }
    opus_int16 pcm[MAX_FRAME_SAMPLES];
    int max_samples = frame_size > MAX_FRAME_SAMPLES ? MAX_FRAME_SAMPLES
                                                     : frame_size;
    int samples = opus_decode(dec, (const unsigned char *) bytes, len, pcm,
                              max_samples, 0);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (samples < 0) {
        return NULL;
    }
    jshortArray result = (*env)->NewShortArray(env, samples);
    if (result != NULL) {
        (*env)->SetShortArrayRegion(env, result, 0, samples, pcm);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_org_oxff_helloxiaozhi_audio_OpusCodec_nativeDestroyEncoder(
        JNIEnv *env, jobject thiz, jlong handle) {
    OpusEncoder *enc = as_encoder(handle);
    if (enc != NULL) {
        opus_encoder_destroy(enc);
    }
}

JNIEXPORT void JNICALL
Java_org_oxff_helloxiaozhi_audio_OpusCodec_nativeDestroyDecoder(
        JNIEnv *env, jobject thiz, jlong handle) {
    OpusDecoder *dec = as_decoder(handle);
    if (dec != NULL) {
        opus_decoder_destroy(dec);
    }
}
