plugins {
    // KGP 由根 build.gradle.kts 的 buildscript classpath 提供，故不声明版本号。
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "me.ethanxu.jevnoisegate.core.common"
        compileSdk = 37
        minSdk = 29

        // AGP 9 的 KMP 插件默认不开 host test；不开启则 commonTest 静默不执行。
        withHostTest {}
    }

    // KMP 的 android 目标不继承 Android 库的 Java 版本设置，默认会用构建 JDK 的目标版本
    // （本机 JDK 26 → 字节码 70.0），消费方的 javac 期望 61.0（Java 17）而报错。
    // jvmToolchain 是 KMP 的标准做法：指定编译用的 JDK，字节码目标随之确定。
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            // AppLog 用 StateFlow 暴露日志流，因此需要协程。
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
