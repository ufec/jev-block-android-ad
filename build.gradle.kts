// AGP 9.0 起内置 Kotlin 支持（android.builtInKotlin 默认 true），不再需要也不能应用
// org.jetbrains.kotlin.android 插件。AGP 9.4.0 对 KGP 的默认运行时依赖低于我们需要的版本，
// 因此通过 buildscript classpath 显式提升 KGP 与 KSP。
//
// 注意：版本号需与 gradle/libs.versions.toml 中的 kotlin / ksp 保持一致。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
