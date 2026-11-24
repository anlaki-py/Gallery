pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Robolectric's large SDK jar hit Maven Central's per-runner HTTP 429 limit.
        exclusiveContent {
            forRepository {
                maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
        google()
        mavenCentral()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}

rootProject.name = "Gallery"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":app")
