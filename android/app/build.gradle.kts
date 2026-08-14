plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "br.com.pulsefitness.kiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.pulsefitness.kiosk"
        // Galaxy Tab A9+ ships with Android 13 (API 33); minSdk 26 leaves margin.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Backend base URL per build type; device token is provisioned at
        // install time (Stage 3), not hardcoded.
        // Override for a one-off build against another environment:
        //   ./gradlew :app:assembleDebug -PapiBase=https://kiosk.pulsefitness.com.br/api/v1/
        val apiBase = (project.findProperty("apiBase") as String?)
            ?: "http://10.0.2.2:8000/api/v1/"
        buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Production. HTTPS is mandatory here: the release build has no
            // cleartext exemption, and students' Hevy keys ride this connection.
            buildConfigField(
                "String", "API_BASE_URL", "\"https://kiosk.pulsefitness.com.br/api/v1/\""
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Offline queue
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Backend API
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Encrypted local storage for the device token
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
}
