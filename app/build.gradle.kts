plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.paw.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.paw.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // arm64-v8a only; widen before a wider release.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            // Oboe's prefab AAR links against the shared STL.
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
        }
    }

    buildTypes {
        // Signed with the debug key so it installs without a keystore; revisit
        // before a real release.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    testOptions {
        unitTests {
            // Robolectric renders the real screens on the JVM (tools/snapshots.sh).
            // Roborazzi always records, so a run regenerates the PNGs.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("roborazzi.test.record", "true")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.oboe:oboe:1.10.0")

    // JVM screen snapshots (app/src/test/kotlin/org/paw/app/snap/).
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}
