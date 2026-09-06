plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nikosm.voiceassistant"
    compileSdk {
        version = release(37)
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.nikosm.voiceassistant"
        minSdk = 31
        targetSdk = 37
        versionCode = 4
        versionName = "1.0.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true // S4: BuildConfig.DEBUG gates conversation-content logging
    }
    // JVM unit tests (SettingsManagerCorruptionTest) exercise the real SettingsManager,
    // whose init-recovery and JSON-corruption guards call android.util.Log, and whose
    // encrypted-prefs branch needs AndroidKeyStore to be unavailable so the constructor
    // falls back to plaintext prefs. Stubbed framework calls must return defaults
    // instead of throwing "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    testImplementation(libs.junit)
    // Real org.json for JVM tests: the android.jar stub's JSONObject returns default
    // values under returnDefaultValues, which would make importLegacyBackup's parsing
    // untestable (has() always false). The real artifact shadows it on the test
    // classpath only.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}