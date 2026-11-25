// Maven Central answers 429 Too Many Requests on GitHub's shared runner IPs (Robolectric's jars, then plain POMs
// such as AGP's bouncycastle BOM), so Central artifacts come from Google's mirror of it first. The mirror can lag
// a fresh release by a little, so Central itself stays as the fallback for anything the mirror does not have yet.
pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Robolectric's android-all-instrumented jars (150-200 MB each) come only from the mirror.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirrorRobolectric" }
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
}
rootProject.name = "robo-bike"
include(":app")
