plugins {
    // KGP 由根 build.gradle.kts 的 buildscript classpath 提供，故不声明版本号。
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "me.ethanxu.jevnoisegate.core.decision"
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
            api(project(":core:model"))

            // TypeSafeBackend 暴露在公开 API 上（app 模块需要构造 TypeSafeClient），
            // 因此 SDK 与它传递暴露的协程、序列化都在 API 面上。
            api(libs.typesafe.sdk)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
