plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

layout.buildDirectory.set(file("build_tmp"))

android {
    namespace = "com.btmicfix"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.btmicfix"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {

    // Compose
    val composeBom =
        platform(
            "androidx.compose:compose-bom:2024.02.00"
        )

    implementation(composeBom)

    implementation(
        "androidx.compose.ui:ui"
    )

    implementation(
        "androidx.compose.ui:ui-graphics"
    )

    implementation(
        "androidx.compose.ui:ui-tooling-preview"
    )

    implementation(
        "androidx.compose.material3:material3"
    )

    implementation(
        "androidx.compose.material:material-icons-extended"
    )

    // Activity + Lifecycle
    implementation(
        "androidx.activity:activity-compose:1.8.2"
    )

    implementation(
        "androidx.lifecycle:lifecycle-runtime-compose:2.7.0"
    )

    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0"
    )

    // Core
    implementation(
        "androidx.core:core-ktx:1.12.0"
    )

    // Shizuku
    val shizukuVersion =
        "13.1.5"

    implementation(
        "dev.rikka.shizuku:api:$shizukuVersion"
    )

    implementation(
        "dev.rikka.shizuku:provider:$shizukuVersion"
    )

    // Hidden Android API access.
    //
    // We still use Shizuku for the privileged Binder call.
    // This library only lets BTMicFix reach the hidden
    // Bluetooth framework interface needed to obtain that Binder.
    implementation(
        "org.lsposed.hiddenapibypass:hiddenapibypass:6.1"
    )

    // Coroutines
    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
    )

    // Tests
    testImplementation(
        "junit:junit:4.13.2"
    )

    testImplementation(
        "io.mockk:mockk:1.13.9"
    )

    testImplementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3"
    )

    androidTestImplementation(
        "androidx.test.ext:junit:1.1.5"
    )

    androidTestImplementation(
        "androidx.test.espresso:espresso-core:3.5.1"
    )

    androidTestImplementation(
        composeBom
    )

    androidTestImplementation(
        "androidx.compose.ui:ui-test-junit4"
    )

    debugImplementation(
        "androidx.compose.ui:ui-tooling"
    )

    debugImplementation(
        "androidx.compose.ui:ui-test-manifest"
    )
}
