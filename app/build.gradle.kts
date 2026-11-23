import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "dev.donrobo.ebike"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.donrobo.ebike"
        minSdk = 31
        targetSdk = 36
        // Read by F-Droid's checkupdates: keep both literal here and bump them for every release tag (v<versionName>).
        versionCode = 2
        versionName = "0.1.1"
    }

    // Release signing from the environment (CI) or keystore.properties (local, gitignored). Without either the release
    // APK stays unsigned, which is what F-Droid wants: it builds from source and signs itself.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    fun signingValue(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }
    val releaseStoreFile = signingValue("ROBOBIKE_KEYSTORE_FILE", "storeFile")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("ROBOBIKE_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ROBOBIKE_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ROBOBIKE_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Own app id: the debug build (simulator, quick tests) installs next to the real app instead of over it,
            // so it never touches the rider's data and never needs the release key.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            vcsInfo.include = false // no git commit id in the APK: reproducible builds
        }
    }

    // No Google-signed dependency metadata block in the APK/AAB: F-Droid and IzzyOnDroid reject it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    androidResources { generateLocaleConfig = true }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric screenshot tests need resources and fonts
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

roborazzi { outputDir.set(layout.buildDirectory.dir("screenshots")) }

// Robolectric's Android framework jars (android-all-instrumented) are resolved by Gradle, not downloaded by
// Robolectric at test time: Gradle retries and caches them, so a flaky Maven download no longer fails random test
// classes. The tests then run Robolectric offline from that directory.
// One jar per SDK the tests use (robolectric.properties: 35, @Config: 31). The versions must match what this
// Robolectric release expects: after a Robolectric bump, offline mode names the missing jar; take its version from
// that error (or from ~/.m2/repository/org/robolectric/android-all-instrumented after an online run).
// One configuration per jar: within a single configuration Gradle would resolve the versions to just the newest.
val robolectricAndroidAll = mapOf(
    31 to "12-robolectric-7732740-i7",
    35 to "15-robolectric-14663667-2-i7",
).map { (sdk, version) ->
    configurations.create("robolectricAndroidAll$sdk") {
        isTransitive = false
        dependencies.add(project.dependencies.create("org.robolectric:android-all-instrumented:$version"))
    }
}
val robolectricJarsDir = layout.buildDirectory.dir("robolectric-android-all")
val prepareRobolectricJars by tasks.registering(Sync::class) {
    from(robolectricAndroidAll)
    into(robolectricJarsDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricJars)
    inputs.dir(robolectricJarsDir)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJarsDir.get().asFile.absolutePath)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("com.materialkolor:material-kolor:4.1.1") // colour scheme from the Robo Bike green, harmonized fixed colours
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0") // per-app language on API 31/32 (AppCompatDelegate)
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.maplibre.gl:android-sdk:13.7.1") // map component (ui/components/map), OpenFreeMap tiles
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.79.0")
}
