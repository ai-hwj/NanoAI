import java.util.Properties

// local.properties is gitignored and may be absent on a fresh clone, so read it defensively:
// a missing file simply yields an empty map and the built-in defaults below remain in effect.
val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

// Resolution order: local.properties -> environment variable -> default.
fun localOr(key: String, fallback: String): String =
    (localProperties.getProperty(key) ?: System.getenv(key) ?: fallback).trim()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lyentech.nano"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lyentech.nano"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 设置支持的SO库架构
            abiFilters += setOf("arm64-v8a")
            // "x86", "x86_64" 如需可自行加回 "armeabi", "armeabi-v7a",
        }

        // GREE cloud chat config. The real wire dialect is unverified, so base URL, path,
        // model name and auth scheme are overridable from local.properties / env vars
        // without code changes. Defaults concatenate to the full company endpoint:
        // https://baitong-aiw.gree.com/openapi/cllm/chat/completions
        buildConfigField("String", "GREE_API_KEY", "\"${localOr("GREE_API_KEY", "")}\"")
        buildConfigField("String", "GREE_BASE_URL", "\"${localOr("GREE_BASE_URL", "https://baitong-aiw.gree.com/openapi/cllm")}\"")
        buildConfigField("String", "GREE_CHAT_PATH", "\"${localOr("GREE_CHAT_PATH", "/chat/completions")}\"")
        buildConfigField("String", "GREE_MODEL", "\"${localOr("GREE_MODEL", "deepseek-v4-flash")}\"")
        buildConfigField("String", "GREE_AUTH_SCHEME", "\"${localOr("GREE_AUTH_SCHEME", "Bearer")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.all {
            // 需要真连外网的测试默认不跑；用 ./gradlew ... -Pnano.live.web / -Pnano.live.gree 打开。
            // gradleProperty 未设置时是 null，getOrElse 给 "false"；显式传 -Pnano.live.web（无值）
            // 时是空字符串，测试侧按"非 false 即开启"判断。
            listOf("nano.live.web", "nano.live.gree").forEach { flag ->
                it.systemProperty(flag, providers.gradleProperty(flag).getOrElse("false").ifEmpty { "true" })
            }
        }
    }

    // google-auth-library / api-common 等 Google 依赖各自打包了 META-INF/INDEX.LIST 与
    // META-INF/DEPENDENCIES，AGP 合并 Java 资源时会因同路径多来源而报
    // DuplicateRelativeFileException。这两个文件对运行时无意义，合并掉即可。
    packaging {
        resources {
            merges += "**/META-INF/INDEX.LIST"
            merges += "**/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    // org.json 在 Android 上由系统提供，但宿主 JVM 的单元测试里只有会抛
    // RuntimeException("not mocked") 的桩实现；云端模型的请求体构造与 SSE 解析都依赖它。
    // 这里只覆盖测试 classpath，APK 仍然使用系统实现，线上依赖面不变。
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // AndroidX
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.navigation3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // KotlinX
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    // Koin BOM + libs
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.core.coroutines)

    // Others
    implementation(libs.bundles.markdown)
    implementation(libs.coil3.network.okhttp)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui) //bundles挺好用

    //filekit
    implementation(libs.file.picker)
    implementation(libs.file.dialog)
    implementation(libs.file.dialog.compose)
    implementation(libs.file.coil)

    //ADK Google
    ksp(libs.adk.processor)
    implementation(libs.adk.core)
    implementation(libs.adk.litertlm)
    implementation(libs.google.litertlm)

    // Room —— 应用自己的库（项目 + 会话元数据）。
    // 必须和 ADK-Kotlin 用同一套经典 androidx.room：ADK 的 RoomSessionService 依赖
    // androidx.room:room-runtime/-ktx 2.8.4，而应用侧的 @Database/@Entity 注解同样是 androidx.room 包名。
    // 之前引的是 androidx.room3:room3-*（包名 androidx.room3），编译期根本没有 androidx.room
    // 的注解，:app:compileDebugKotlin 直接 "Unresolved reference 'room'"。
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 联网搜索 / 网页正文提取
    implementation(libs.jsoup)
    implementation(libs.flexmark.html2md)


}