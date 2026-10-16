plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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

        ndk {
            abiFilters += providers.gradleProperty("targetAbi").orNull
                ?.let { listOf(it) } ?: listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tflite", "bin")
        // Optional local verification models never enter the APK.
        ignoreAssetsPattern = "laya"
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

    implementation(libs.litert)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
