pluginManagement {
    repositories {
        // Local marker keeps AGP resolution deterministic in restricted/offline environments.
        maven { url = uri(rootDir.resolve("gradle/plugin-markers")) }
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
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Manfaz VPN"
include(":app")
