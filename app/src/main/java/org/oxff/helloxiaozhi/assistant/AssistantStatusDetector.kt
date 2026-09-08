package org.oxff.helloxiaozhi.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * 系统默认语音助手状态检测（兼容国产定制 ROM）。
 *
 * ## 检测原理与分层
 * Android 标准里「默认数字助理应用」有两套并存机制：
 *  - Android 10+（API 29+）：[RoleManager.ROLE_ASSISTANT] 角色（权威来源）；
 *  - 兼容层：Settings.Secure 的 voice_interaction_service / assistant 两个键，
 *    由框架在角色变化时回写（AOSP VoiceInteractionManagerService / DefaultAssistPreference 确认）。
 *
 * 但国产定制 ROM 常改写设置入口、把结果存到**私有键**，导致标准键与角色都为空。
 * 由于这些私有键名无公开文档、只能真机实测，本检测采用分层并集 + 通用兜底：
 *
 *  1. 角色：`RoleManager.isRoleHeld`（公开 API，API 29+）；
 *  2. 角色（反射兜底）：`RoleManager.getRoleHolders`（隐藏 API，所有 10+ 设备存在，
 *     即便厂商改了 UI 存储，角色服务通常仍维护）——覆盖未知 ROM 的通用手段；
 *  3. 标准 Secure 键：voice_interaction_service / assistant；
 *  4. 已知私有 Secure 键：一加/OPlus 实测键（见 [PRIVATE_SECURE_KEYS]）。
 *
 * 任一来源指向本应用即视为已设置；比较统一按 [ComponentName] 解析后与包名精确相等，
 * 替代子串匹配避免误报。检测失败时把各来源原始值打到 logcat（TAG=[TAG]），
 * 便于用户上报尚未覆盖的 ROM 私有键。
 */
object AssistantStatusDetector {

    private const val TAG = "AssistantStatusDetector"

    // 对应 Settings.Secure.VOICE_INTERACTION_SERVICE / ASSISTANT
    // （常量 API 23/21 才引入，minSdk 21 下用字面量避免框架 API 门槛）
    private const val KEY_VOICE_INTERACTION_SERVICE = "voice_interaction_service"
    private const val KEY_ASSISTANT = "assistant"

    /**
     * 已知国产 ROM 私有 Secure 键（真机实测确认，持续补充）。
     *
     * 一加（OxygenOS/ColorOS，OPlus 体系，realme 大概率共用）：
     * 设置默认语音助手后，标准键与 ROLE_ASSISTANT 角色均为空，
     * 仅此私有键指向本应用组件（OnePlus 7T / Android 11 实测）。
     */
    private val PRIVATE_SECURE_KEYS = listOf(
        "oneplus_default_voice_assist_picker_service",
    )

    /** 本应用当前是否为系统默认语音助手。 */
    fun isDefaultAssistant(context: Context): Boolean {
        val pkg = context.packageName

        // 1. 角色（公开 API，Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAssistantRoleHolder(context)) {
            return true
        }
        // 2. 角色（反射隐藏 API 兜底，覆盖厂商改了 UI 存储但角色服务仍维护的 ROM）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAssistantRoleHolderReflect(context, pkg)) {
            return true
        }

        // 3. 标准 Secure 键 + 4. 已知私有 Secure 键
        val resolver = context.contentResolver
        val standardHit = flatRefersToPackage(
            Settings.Secure.getString(resolver, KEY_VOICE_INTERACTION_SERVICE), pkg,
        ) || flatRefersToPackage(
            Settings.Secure.getString(resolver, KEY_ASSISTANT), pkg,
        )
        if (standardHit) return true

        for (key in PRIVATE_SECURE_KEYS) {
            if (flatRefersToPackage(Settings.Secure.getString(resolver, key), pkg)) {
                return true
            }
        }

        // 全部未命中：输出诊断信息，便于上报未覆盖的 ROM
        logDiagnostics(context, pkg)
        return false
    }

    /** Android 10+：本应用是否为 ROLE_ASSISTANT（默认数字助理）角色持有者（公开 API）。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isAssistantRoleHolder(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return try {
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } catch (t: Throwable) {
            Log.w(TAG, "isRoleHeld 调用失败", t)
            false
        }
    }

    /**
     * Android 10+：通过反射调用隐藏 API `RoleManager.getRoleHolders()` 取角色持有者列表。
     *
     * 部分国产 ROM 改写了设置 UI 的存储路径，但底层 RoleManagerService 的角色记录
     * 通常仍正确维护；该隐藏 API 在所有 Android 10+ 设备上存在，作为通用兜底。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isAssistantRoleHolderReflect(context: Context, pkg: String): Boolean {
        return try {
            val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
            @Suppress("UNCHECKED_CAST")
            val holders = roleManager.javaClass
                .getMethod("getRoleHolders", String::class.java)
                .invoke(roleManager, RoleManager.ROLE_ASSISTANT) as? List<String>
            holders?.contains(pkg) == true
        } catch (t: Throwable) {
            // 反射失败（API 被移除/限制）属预期，静默降级
            false
        }
    }

    /** 检测未命中时输出各来源原始值，便于定位未覆盖的 ROM 私有键。 */
    private fun logDiagnostics(context: Context, pkg: String) {
        try {
            val resolver = context.contentResolver
            val sb = StringBuilder()
            sb.append("默认助手检测未命中 pkg=").append(pkg)
            sb.append(" | voice_interaction_service=")
                .append(Settings.Secure.getString(resolver, KEY_VOICE_INTERACTION_SERVICE))
            sb.append(" | assistant=")
                .append(Settings.Secure.getString(resolver, KEY_ASSISTANT))
            for (key in PRIVATE_SECURE_KEYS) {
                sb.append(" | ").append(key).append("=")
                    .append(Settings.Secure.getString(resolver, key))
            }
            sb.append(" | manufacturer=").append(Build.MANUFACTURER)
                .append(" brand=").append(Build.BRAND)
                .append(" sdk=").append(Build.VERSION.SDK_INT)
            Log.i(TAG, sb.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "诊断日志输出失败", t)
        }
    }

    /**
     * Secure 键存储的组件串是否指向 [pkg]。
     *
     * 系统写入形式为 ComponentName flatten 串（flattenToShortString 的
     * "pkg/.Class" 或 flattenToString 的 "pkg/pkg.Class"），个别 ROM 也可能
     * 只存纯包名；空串表示用户显式关闭（role 清空时框架写 ""），不能视为命中。
     */
    internal fun flatRefersToPackage(flat: String?, pkg: String): Boolean {
        if (flat.isNullOrEmpty()) return false
        if (flat == pkg) return true
        return ComponentName.unflattenFromString(flat)?.packageName == pkg
    }
}
