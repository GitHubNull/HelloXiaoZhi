package org.oxff.helloxiaozhi.assistant

import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 系统语音助手状态检测单元测试。
 *
 * 覆盖两类行为：
 * 1. [AssistantStatusDetector.flatRefersToPackage]：Secure 键存储串的精确包名匹配
 *    （flatten 长短形式、纯包名、空串/其他包/子串相似包不可误报）；
 * 2. [AssistantStatusDetector.isDefaultAssistant]：Robolectric 环境下通过
 *    Settings.Secure 键驱动整体判定（role 分支在 Robolectric 中 holder 为空，
 *    等价于无 role 系统的回落路径）。
 */
@RunWith(RobolectricTestRunner::class)
class AssistantStatusDetectorTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val pkg: String = context.packageName

    // 服务短类名（与 AndroidManifest 中注册保持一致）
    private val shortFlat = "$pkg/.assistant.XiaoZhiVoiceInteractionService"
    private val fullFlat = "$pkg/$pkg.assistant.XiaoZhiVoiceInteractionService"

    @Before
    fun clearSecureKeys() {
        Settings.Secure.putString(context.contentResolver, KEY_VOICE_INTERACTION, null)
        Settings.Secure.putString(context.contentResolver, KEY_ASSISTANT, null)
        Settings.Secure.putString(context.contentResolver, KEY_ONEPLUS_VOICE_ASSIST, null)
    }

    // ---------------- flatRefersToPackage：精确匹配 ----------------

    @Test
    fun flatRefersToPackage_nullAndEmpty_areFalse() {
        assertFalse(AssistantStatusDetector.flatRefersToPackage(null, pkg))
        assertFalse(AssistantStatusDetector.flatRefersToPackage("", pkg))
    }

    @Test
    fun flatRefersToPackage_shortFlat_matches() {
        assertTrue(AssistantStatusDetector.flatRefersToPackage(shortFlat, pkg))
    }

    @Test
    fun flatRefersToPackage_fullFlat_matches() {
        assertTrue(AssistantStatusDetector.flatRefersToPackage(fullFlat, pkg))
    }

    @Test
    fun flatRefersToPackage_plainPackage_matches() {
        assertTrue(AssistantStatusDetector.flatRefersToPackage(pkg, pkg))
    }

    @Test
    fun flatRefersToPackage_otherPackage_isFalse() {
        assertFalse(AssistantStatusDetector.flatRefersToPackage("com.android.settings/.SomeService", pkg))
    }

    @Test
    fun flatRefersToPackage_similarPrefixPackage_isFalse() {
        // 回归用例：旧实现用 contains 子串匹配，此类值会误报
        assertFalse(AssistantStatusDetector.flatRefersToPackage("$pkg.evil/.assistant.XiaoZhiVoiceInteractionService", pkg))
        assertFalse(AssistantStatusDetector.flatRefersToPackage("evil.$pkg/.assistant.XiaoZhiVoiceInteractionService", pkg))
    }

    // ---------------- isDefaultAssistant：多数据源整体判定 ----------------

    @Test
    fun isDefaultAssistant_noSettings_isFalse() {
        assertFalse(AssistantStatusDetector.isDefaultAssistant(context))
    }

    @Test
    fun isDefaultAssistant_voiceInteractionKey_matches() {
        Settings.Secure.putString(context.contentResolver, KEY_VOICE_INTERACTION, shortFlat)
        assertTrue(AssistantStatusDetector.isDefaultAssistant(context))
    }

    @Test
    fun isDefaultAssistant_voiceInteractionKey_fullFlat_matches() {
        Settings.Secure.putString(context.contentResolver, KEY_VOICE_INTERACTION, fullFlat)
        assertTrue(AssistantStatusDetector.isDefaultAssistant(context))
    }

    @Test
    fun isDefaultAssistant_legacyAssistantKey_matches() {
        // Android 9- / 部分 ROM 只写 assistant 键
        Settings.Secure.putString(context.contentResolver, KEY_ASSISTANT, shortFlat)
        assertTrue(AssistantStatusDetector.isDefaultAssistant(context))
    }

    @Test
    fun isDefaultAssistant_oneplusKey_matches() {
        // 一加 ROM：标准键与角色均为空，仅私有键指向本应用（真机实测场景）
        Settings.Secure.putString(context.contentResolver, KEY_ONEPLUS_VOICE_ASSIST, shortFlat)
        assertTrue(AssistantStatusDetector.isDefaultAssistant(context))
    }

    @Test
    fun isDefaultAssistant_oneplusKey_otherPackage_isFalse() {
        Settings.Secure.putString(
            context.contentResolver,
            KEY_ONEPLUS_VOICE_ASSIST,
            "com.oneplus.brickmode/.assistant.SomeService",
        )
        assertFalse(AssistantStatusDetector.isDefaultAssistant(context))
    }

    @Test
    fun isDefaultAssistant_explicitlyDisabledEmptyString_isFalse() {
        // role 清空时框架会把两个键写为空串，表示用户显式关闭
        Settings.Secure.putString(context.contentResolver, KEY_VOICE_INTERACTION, "")
        Settings.Secure.putString(context.contentResolver, KEY_ASSISTANT, "")
        assertFalse(AssistantStatusDetector.isDefaultAssistant(context))
    }

    @Test
    fun isDefaultAssistant_otherAssistant_isFalse() {
        Settings.Secure.putString(
            context.contentResolver,
            KEY_VOICE_INTERACTION,
            "com.android.settings/.assistant.SettingsVoiceInteractionService",
        )
        assertFalse(AssistantStatusDetector.isDefaultAssistant(context))
    }

    @Test
    fun isDefaultAssistant_similarPrefixPackage_isFalse() {
        Settings.Secure.putString(context.contentResolver, KEY_VOICE_INTERACTION, "$pkg.fake/.assistant.XiaoZhiVoiceInteractionService")
        assertFalse(AssistantStatusDetector.isDefaultAssistant(context))
    }

    private companion object {
        const val KEY_VOICE_INTERACTION = "voice_interaction_service"
        const val KEY_ASSISTANT = "assistant"
        const val KEY_ONEPLUS_VOICE_ASSIST = "oneplus_default_voice_assist_picker_service"
    }
}
