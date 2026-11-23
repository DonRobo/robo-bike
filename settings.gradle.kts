pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Robolectric's android-all-instrumented jars (150-200 MB each) come only from Google's mirror of Maven
        // Central: Maven Central answers 429 Too Many Requests for them on GitHub's shared runner IPs.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
        mavenCentral()
    }
}
rootProject.name = "robo-bike"
include(":app")
