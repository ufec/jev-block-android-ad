plugins {
    // KGP 由根 build.gradle.kts 的 buildscript classpath 提供，故不声明版本号。
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "me.ethanxu.jevnoisegate.sdk.typesafe"
        compileSdk = 37
        minSdk = 29

        // AGP 9 的 KMP 插件默认不开 host test；不开启则 commonTest 静默不执行。
        withHostTest {}
    }

    // KMP 的 android 目标不继承 Android 库的 Java 版本设置，默认会用构建 JDK
    // 的目标版本（本机 JDK 26 → 字节码 70.0），消费方 javac 期望 61.0（Java 17）
    // 而报错。jvmToolchain 是 KMP 的标准做法：指定编译用的 JDK，字节码目标随之确定。
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            // 公开 API 里出现 JsonElement（EntryType）与 suspend 函数，故用 api 暴露。
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)

            // TypeSafeClient 的构造参数可注入 HttpClient，便于测试；因此 Ktor core 也在 API 面上。
            api(libs.ktor.client.core)
        }

        // HTTP 引擎是平台相关的：OkHttp 引擎只能在 JVM/Android 上跑，
        // 因此放在 androidMain，并通过 expect/actual 暴露给 commonMain。
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
