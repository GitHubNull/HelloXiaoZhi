// Top-level build file where you can add configuration options common to all sub-projects/modules.
// 注意：AGP 9 内置 Kotlin 支持，不能显式应用 org.jetbrains.kotlin.android 插件（会与内置扩展冲突）
plugins {
    alias(libs.plugins.android.application) apply false
}