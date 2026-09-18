plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "me.ethanxu.jevnoisegate.core.dispatch"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

/**
 * 采集端与流水线之间的 Android 胶水层。
 *
 * 单独成模块而不是塞进 :core:pipeline，是被工具链逼出来的，但结果反而更干净：
 *
 * :core:pipeline 是 KMP 模块，它的 androidMain 里放 Hilt 的 @InstallIn 模块会**不生效** ——
 * AGP 9 的 com.android.kotlin.multiplatform.library 产出的结构不满足 Hilt 聚合任务的要求，
 * Dagger 会报 MissingBinding。而纯 Android 库模块里 Hilt 一切正常。
 *
 * 于是形成清晰的分层：:core:pipeline 只留纯编排逻辑（零平台依赖、可 JVM 毫秒级测试），
 * 需要 Room 与 Hilt 的调度器隔离在这里。
 */
dependencies {
    api(project(":core:model"))
    api(project(":core:decision"))
    api(project(":core:pipeline"))

    implementation(project(":core:data"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
