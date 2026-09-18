plugins {
    // KGP 已由根 build.gradle.kts 的 buildscript classpath 提供（用于把 AGP 内置的
    // Kotlin 从 2.2.10 提升到 2.4.20）。classpath 上已有的插件不能再声明版本号，
    // 否则 Gradle 会报 "already on the classpath with an unknown version"。
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    // 注意：AGP 9 的 KMP 插件里该块叫 android { }（androidLibrary 已废弃）。
    // 这是 KMP 模块的 Android 目标配置 —— 它替代了 KMP 模块中不可用的顶层 android { }，
    // 且库模块不再支持 targetSdk（只由 app 模块决定）。
    android {
        namespace = "me.ethanxu.jevnoisegate.core.model"
        compileSdk = 37
        minSdk = 29

        // AGP 9 的 KMP 插件默认不开 host test。不开启的话 commonTest 不会被连到任何
        // 编译单元（构建会给出 "Unused Kotlin Source Sets" 警告），测试根本不会运行。
        withHostTest {}
    }

    // KMP 的 android 目标不继承 Android 库的 Java 版本设置，默认会用构建 JDK
    // 的目标版本（本机 JDK 26 → 字节码 70.0），消费方 javac 期望 61.0（Java 17）
    // 而报错。jvmToolchain 是 KMP 的标准做法：指定编译用的 JDK，字节码目标随之确定。
    jvmToolchain(17)

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
