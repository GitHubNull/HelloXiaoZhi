    plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.oxff.helloxiaozhi"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.oxff.helloxiaozhi"
        minSdk = 21
        targetSdk = 27
        versionCode = 7
        versionName = "0.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    lint {
        disable += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    testImplementation(libs.junit)
    // ASR 测试评估体系：模拟小智 WebSocket 服务器（仅测试依赖）
    testImplementation(libs.mockwebserver)
    testImplementation(libs.gson)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.rules)
}

// ---------------- ASR 测试评估体系（非侵入：仅测试入口，不影响业务构建） ----------------

// 通过 `gradlew asrTest` 执行时，只运行 ASR 评估测试；常规 test 不受影响
tasks.withType<Test>().configureEach {
    if (gradle.startParameter.taskNames.any { it.contains("asrTest") }) {
        filter.includeTestsMatching("org.oxff.helloxiaozhi.asr.*")
    }
}

tasks.register("asrTest") {
    group = "verification"
    description = "运行 ASR 测试评估体系（org.oxff.helloxiaozhi.asr.*）并生成评分报告"
    dependsOn("testDebugUnitTest")
}

// 真实音频资源管理：从开放平台下载/更新测试音频（需先安装 tools/asr 虚拟环境）
tasks.register<Exec>("asrFetchResources") {
    group = "verification"
    description = "下载/更新 ASR 真实音频测试资源（B 站等开放平台，需 uv 虚拟环境）"
    workingDir = rootDir
    commandLine(
        rootDir.resolve(".venv-asr/Scripts/python.exe").absolutePath,
        rootDir.resolve("tools/asr/fetch_real_world_resources.py").absolutePath
    )
}