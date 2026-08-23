package org.oxff.helloxiaozhi.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AudioRecord 采样率探测测试：验证 AudioRecorderManager 的
 * 16kHz 首选 / 44.1kHz 兜底策略在设备上至少有一条可用路径。
 */
@RunWith(AndroidJUnit4::class)
class AudioRecordProbeInstrumentedTest {

    @get:Rule
    val recordAudioRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    // 注意：androidTest 会被 DEX 化，方法名不能含空格（反引号命名会导致 DEX 构建失败）
    @Test
    fun testAtLeastOneSampleRateAvailable() {
        val rate16k = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val rate44k = AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        assertTrue(
            "16kHz($rate16k) 与 44.1kHz($rate44k) 应至少一种可用",
            rate16k > 0 || rate44k > 0,
        )
    }

    @Test
    fun testProbedSampleRateCreatesAudioRecord() {
        val candidates = intArrayOf(16000, 44100)
        var created = false
        var lastError: Exception? = null
        for (rate in candidates) {
            if (AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ) <= 0
            ) {
                continue
            }
            try {
                // AudioRecord 不实现 Closeable（API 21），需手动 release
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    AudioRecord.getMinBufferSize(
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ) * 2,
                )
                try {
                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        // 诊断输出：无权限或设备不支持该音源时 state 为 UNINITIALIZED
                        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                        val granted = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        android.util.Log.e(
                            "AudioRecordProbe",
                            "DIAG rate=$rate state=${record.state} " +
                                "recordPermissionGranted=$granted",
                        )
                        continue
                    }
                    created = true
                } finally {
                    record.release()
                }
                break
            } catch (e: Exception) {
                lastError = e
                android.util.Log.e("AudioRecordProbe", "DIAG rate=$rate create failed: $e")
            }
        }
        assertTrue("应至少能创建一种采样率的 AudioRecord: $lastError", created)
    }
}
