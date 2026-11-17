import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.corepack.pet"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.corepack.pet"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // Where the app connects, and an optional invite code baked in, are chosen when the APK is built:
        //   ./gradlew assembleDebug -PgatewayUrl=https://your-server/v1 -PinviteCode=cpt_...
        val gatewayUrl = (project.findProperty("gatewayUrl") as String?) ?: "http://127.0.0.1:8787/v1"
        val inviteCode = (project.findProperty("inviteCode") as String?) ?: ""
        buildConfigField("String", "DEFAULT_GATEWAY_URL", "\"$gatewayUrl\"")
        buildConfigField("String", "DEFAULT_INVITE", "\"$inviteCode\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
