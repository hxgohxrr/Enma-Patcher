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
        // ReVanced packages via GitHub Packages (requires PAT in ~/.gradle/gradle.properties)
        // gpr.user = your GitHub username
        // gpr.key  = PAT with read:packages scope
        maven {
            url = uri("https://maven.pkg.github.com/ReVanced/revanced-patcher")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse("token").get()
                password = providers.gradleProperty("gpr.key").orElse("").get()
            }
        }
        maven {
            url = uri("https://maven.pkg.github.com/ReVanced/apktool")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse("token").get()
                password = providers.gradleProperty("gpr.key").orElse("").get()
            }
        }
        maven {
            url = uri("https://maven.pkg.github.com/ReVanced/multidexlib2")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse("token").get()
                password = providers.gradleProperty("gpr.key").orElse("").get()
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "EnmaPatcher"
include(":app")
