package org.oxff.helloxiaozhi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * MAC 生成与校验单元测试。
 *
 * 格式正确性是有实际后果的：机器人的 MAC 会直接作为 WebSocket 握手的
 * Device-Id 与 OTA 注册的 mac_address 上行，格式不符会被服务端拒绝。
 */
class MacGeneratorTest {

    private val pattern = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    @Test
    fun `生成的 MAC 为小写冒号分隔的六段十六进制`() {
        repeat(200) {
            val mac = MacGenerator.random()
            assertTrue("非法格式: $mac", pattern.matches(mac))
        }
    }

    @Test
    fun `同一随机种子生成结果稳定`() {
        val a = MacGenerator.random(Random(42))
        val b = MacGenerator.random(Random(42))

        assertEquals(a, b)
    }

    @Test
    fun `校验接受小写与大写形式`() {
        assertTrue(MacGenerator.isValid("aa:bb:cc:dd:ee:ff"))
        assertTrue(MacGenerator.isValid("AA:BB:CC:DD:EE:FF"))
        assertTrue(MacGenerator.isValid("Aa:bB:0c:9D:e0:1f"))
    }

    @Test
    fun `校验忽略首尾空白`() {
        assertTrue(MacGenerator.isValid("  aa:bb:cc:dd:ee:ff  "))
    }

    @Test
    fun `校验拒绝段数不足或过多`() {
        assertFalse(MacGenerator.isValid("aa:bb:cc:dd:ee"))
        assertFalse(MacGenerator.isValid("aa:bb:cc:dd:ee:ff:00"))
    }

    @Test
    fun `校验拒绝非十六进制与错误分隔符`() {
        assertFalse(MacGenerator.isValid("gg:bb:cc:dd:ee:ff"))
        assertFalse(MacGenerator.isValid("aa-bb-cc-dd-ee-ff"))
        assertFalse(MacGenerator.isValid("aabbccddeeff"))
        assertFalse(MacGenerator.isValid(""))
        assertFalse(MacGenerator.isValid("随便写点什么"))
    }

    @Test
    fun `校验拒绝单字符分段`() {
        assertFalse(MacGenerator.isValid("a:b:c:d:e:f"))
    }

    @Test
    fun `归一化转为小写并去空白`() {
        assertEquals("aa:bb:cc:dd:ee:ff", MacGenerator.normalize("  AA:BB:cc:Dd:EE:ff "))
    }
}
