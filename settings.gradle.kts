pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // TypeSafe Kotlin SDK，由 JitPack 从 ufec/typesafe-sdk-kotlin 按需构建。
        //
        // 注意 groupId 是 `com.github.ufec.typesafe-sdk-kotlin` 而不是 `com.github.ufec`：
        // JitPack 对多模块工程用 `com.github.<用户>.<仓库>` 作为 group，另在
        // `com.github.<用户>` 下生成一个只做转发的聚合 POM。用精确的 includeGroup
        // 会把真正承载 android 变体的那个 group 挡在外面，表现为"依赖找不到"。
        // 放在最后并限定 group，避免 Gradle 为找其他依赖也去问 JitPack。
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.ufec.*") }
        }
    }
}

rootProject.name = "JevNoiseGate"

include(":app")
include(":core:common")
include(":core:model")
include(":core:fingerprint")
include(":core:decision")
include(":core:data")
include(":core:pipeline")
include(":core:dispatch")
include(":feature:notification")
include(":feature:sms")
