    plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.oxff.helloxiaozhi"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.oxff.helloxiaozhi"
        minSdk = 21
        targetSdk = 27
        versionCode = 25
        versionName = "0.17.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    signingConfigs {
        create("platform") {
            storeFile = file("../platform.jks")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Visbot 机器人控制需要系统签名
            signingConfig = signingConfigs["platform"]
        }
        release {
            // CI 自动发布：以 runner 生成的 debug keystore 签名，保证 Release APK 可直接安装；
            // 后续正式发版可替换为上传签名密钥（secrets）方案。
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Visbot 机器人 SDK（rosa.jar）
    implementation(files("libs/rosa.jar"))
    // sherpa-onnx 的 JitPack AAR 会传递引入 JVM 实现 jar（sherpa-onnx-jvm），
    // 与 Android AAR 内的 Kotlin 类重复冲突，需排除仅保留 AAR（含 native .so）
    implementation(libs.sherpa.onnx) {
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
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