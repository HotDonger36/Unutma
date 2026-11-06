plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.unutma"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // The app's id on phones and in the Play Store. It can't change once people have installed it.
        // (The code's package, namespace above, stays com.example.unutma; that's only inside the code.)
        applicationId = "app.unutma"
        resValue("string", "app_name", "Unutma")
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing key. GitHub builds the APK with the key saved in the repo's Secrets
    // (see .github/workflows/release.yml). On your PC these aren't set, so Android Studio's
    // "Generate Signed APK" keeps working as before.
    val keystoreFile = System.getenv("UNUTMA_KEYSTORE_FILE")
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("UNUTMA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UNUTMA_KEY_ALIAS")
                keyPassword = System.getenv("UNUTMA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Test builds install as a separate app ("Unutma (debug)"), so they never touch your real notes.
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Unutma (debug)")
        }
        release {
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true // the app name is set per build type (see buildTypes)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose) // refresh the calendar when you come back to the app
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Reads text in photos ("Extract text" on a picture), on the phone, no internet needed
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
