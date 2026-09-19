import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// TypeSafe API key 从 local.properties 读取，不入库（.gitignore 已排除该文件）。
//
// 需要留意：打进 BuildConfig 的字符串可以从 APK 里提取出来。侧载自用可接受，
// 但若要公开分发，应改为让用户在自己的设备上填写、存进加密存储。
val typesafeApiKey: String = run {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return@run ""
    val properties = Properties()
    file.inputStream().use(properties::load)
    properties.getProperty("typesafe.apiKey").orEmpty()
}

android {
    namespace = "me.ethanxu.jevnoisegate.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.ethanxu.jevnoisegate"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"

        buildConfigField("String", "TYPESAFE_API_KEY", "\"$typesafeApiKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // 开 R8 与资源裁剪。不开的话这个包是 51 MiB —— 因为 material-icons-extended
            // 那类"库里绝大多数代码用不到"的依赖会原样进包（详细账见 AppIcons.kt 的注释）。
            // 实测：开之前 51.4 MiB，开之后 3.7 MiB。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:fingerprint"))
    implementation(project(":core:decision"))
    implementation(project(":core:data"))
    implementation(project(":core:dispatch"))
    implementation(project(":feature:notification"))
    implementation(project(":feature:sms"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // UI 基础设施：导航与动态取色
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.material.kolor)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 代理探测直接用 OkHttp，不经 SDK —— 理由见 ProxyProbe.kt（SDK 客户端带重试，
    // 会把"代理不通"拖成三十秒才报错；且它同时验证 API Key，两件事会混在一起）。
    implementation(libs.okhttp)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
