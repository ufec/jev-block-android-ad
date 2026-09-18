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
include(":sdk:typesafe")
