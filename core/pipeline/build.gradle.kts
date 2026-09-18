plugins {
    // KGP 由根 build.gradle.kts 的 buildscript classpath 提供，故不声明版本号。
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "me.ethanxu.jevnoisegate.core.pipeline"
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
        // 刻意保持零平台依赖：编排逻辑只依赖后端接口与时钟，不碰 Room、不碰 Hilt，
        // 因此整个流程可以在 JVM 上毫秒级测试。
        //
        // 需要 Room 与 Hilt 的调度层在 :core:dispatch（纯 Android 库）里 ——
        // 本模块的 androidMain 中放 Hilt 的 @InstallIn 模块会不生效（Dagger 报
        // MissingBinding），因为 AGP 9 的 KMP 库插件产出的结构不满足 Hilt 聚合任务的要求。
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:decision"))
            api(project(":core:common"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
