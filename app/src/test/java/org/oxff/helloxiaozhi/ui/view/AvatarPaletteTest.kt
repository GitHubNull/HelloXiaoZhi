package org.oxff.helloxiaozhi.ui.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 头像调色板索引归一化单元测试。
 *
 * 持久化保存的是索引而非渐变本身，存量数据迁移或手工改档可能带来越界值，
 * 归一化必须回绕而不是抛异常。
 */
class AvatarPaletteTest {

    @Test
    fun `有效索引原样返回`() {
        for (i in 0 until AvatarPalette.SIZE) {
            assertEquals(i, AvatarPalette.normalize(i))
        }
    }

    @Test
    fun `越界索引回绕`() {
        assertEquals(0, AvatarPalette.normalize(AvatarPalette.SIZE))
        assertEquals(1, AvatarPalette.normalize(AvatarPalette.SIZE + 1))
        assertEquals(3, AvatarPalette.normalize(AvatarPalette.SIZE * 2 + 3))
    }

    @Test
    fun `负索引回绕到尾部`() {
        assertEquals(AvatarPalette.SIZE - 1, AvatarPalette.normalize(-1))
        assertEquals(0, AvatarPalette.normalize(-AvatarPalette.SIZE))
    }

    @Test
    fun `调色板数量与设计稿一致`() {
        // add-bot-modal.js 的 AVATAR_COLORS 共 8 组
        assertEquals(8, AvatarPalette.SIZE)
    }
}
