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
        versionCode = 1
        versionName = "0.1.0"

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
            isMinifyEnabled = false
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
    implementation(libs.androidx.compose.material.icons.extended)
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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
