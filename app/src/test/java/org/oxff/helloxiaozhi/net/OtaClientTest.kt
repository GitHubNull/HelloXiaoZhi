package org.oxff.helloxiaozhi.net

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTA 客户端单元测试：payload 序列化逐字段对齐
 * websocket_proxy.py，响应 activation 字段解析正确。
 */
class OtaClientTest {

    private val client = OtaClient(OkHttpClient(), Gson())
    private val gson = Gson()

    @Test
    fun `payload 固件信息字段与官方代理一致`() {
        val payload = client.buildPayload("aa:bb:cc:dd:ee:ff", "test-uuid", "192.168.1.5")

        assertEquals(2, payload["version"])
        assertEquals(16777216, payload["flash_size"])
        assertEquals(0, payload["psram_size"])
        assertEquals(8318916, payload["minimum_free_heap_size"])
        assertEquals("aa:bb:cc:dd:ee:ff", payload["mac_address"])
        assertEquals("test-uuid", payload["uuid"])
        assertEquals("esp32s3", payload["chip_model_name"])

        @Suppress("UNCHECKED_CAST")
        val chipInfo = payload["chip_info"] as Map<String, Any?>
        assertEquals(9, chipInfo["model"])
        assertEquals(2, chipInfo["cores"])
        assertEquals(2, chipInfo["revision"])
        assertEquals(18, chipInfo["features"])

        @Suppress("UNCHECKED_CAST")
        val application = payload["application"] as Map<String, Any?>
        assertEquals("xiaozhi", application["name"])
        assertEquals("1.1.2", application["version"])
        assertEquals("v5.3.2-dirty", application["idf_version"])

        assertEquals(emptyList<Any>(), payload["partition_table"])

        @Suppress("UNCHECKED_CAST")
        val ota = payload["ota"] as Map<String, Any?>
        assertEquals("factory", ota["label"])

        @Suppress("UNCHECKED_CAST")
        val board = payload["board"] as Map<String, Any?>
        assertEquals("bread-compact-wifi", board["type"])
        assertEquals("192.168.1.5", board["ip"])
        assertEquals("aa:bb:cc:dd:ee:ff", board["mac"])
    }

    @Test
    fun `未激活响应解析出验证码`() {
        val response = OtaResponse.fromJson(
            gson,
            """{"activation":{"code":"123456","message":"请到控制台激活"}}""",
        )
        assertEquals("123456", response.activationCode)
        assertEquals("请到控制台激活", response.activation?.message)
    }

    @Test
    fun `已激活响应 activation 字段缺失 验证码为空`() {
        val response = OtaResponse.fromJson(gson, """{"firmware":{"version":"1.0"}}""")
        assertNull(response.activation)
        assertNull(response.activationCode)
    }

    @Test
    fun `activation 字段为空对象时验证码为空`() {
        val response = OtaResponse.fromJson(gson, """{"activation":{}}""")
        assertNull(response.activationCode)
    }

    @Test
    fun `非法 JSON 解析返回空响应不抛异常`() {
        val response = OtaResponse.fromJson(gson, "not-json")
        assertNull(response.activation)
        assertNull(response.activationCode)
    }

    @Test
    fun `空验证码字符串视为未激活`() {
        val response = OtaResponse.fromJson(gson, """{"activation":{"code":""}}""")
        assertNull(response.activationCode)
        assertTrue(response.activation != null)
    }
}
