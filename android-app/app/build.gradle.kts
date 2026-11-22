// Release properties are supplied by the verified GitHub Actions release job.
val releaseVersionName = providers.gradleProperty("codexReleaseVersionName").orNull
val releaseVersionCode = providers.gradleProperty("codexReleaseVersionCode").orNull?.toIntOrNull()
val releaseTargetAbi = providers.gradleProperty("codexReleaseTargetAbi").orNull
require(releaseTargetAbi == null || releaseTargetAbi == "arm64-v8a") {
    "正式发布目标架构只能是 arm64-v8a"
}
val signingKeystorePath = System.getenv("CODEX_RELEASE_KEYSTORE_PATH")
val signingPassword = System.getenv("CODEX_RELEASE_PASSWORD")
val signingAlias = System.getenv("CODEX_RELEASE_KEY_ALIAS") ?: "111-release"
val signingReady = !signingKeystorePath.isNullOrBlank() && !signingPassword.isNullOrBlank()
val releaseTasksRequested = gradle.startParameter.taskNames.any {
    it.contains("assembleRelease") || it.contains("bundleRelease") || it.contains("packageRelease")
}
if (releaseTasksRequested) {
    require(releaseVersionName?.matches(Regex("""\d+\.\d+\.\d+-111\.\d+""")) == true) {
        "正式构建必须传入 -PcodexReleaseVersionName=0.1.0-111.N"
    }
    require(releaseVersionCode != null && releaseVersionCode in 1..2100000000) {
        "正式构建必须传入合法的 -PcodexReleaseVersionCode"
    }
    require(signingReady) { "正式构建缺少持久化签名密钥及密码" }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.sy220284.codexandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sy220284.codexandroid"
        minSdk = 29
        targetSdk = 35
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingPassword
                keyAlias = signingAlias
                keyPassword = signingPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libcodex_server.so"
            keepDebugSymbols += "**/libcodex_code_mode_host.so"
            keepDebugSymbols += "**/libcodex_rg.so"
        }
    }
    // Enabled only in the native APK pipeline. Standalone UI verification keeps
    // its compact APK and does not require any of the runtime native binaries.
    splits {
        abi {
            isEnable = providers.gradleProperty("codexSplitAbi").orNull == "true"
            reset()
            if (releaseTargetAbi == "arm64-v8a") {
                include("arm64-v8a")
            } else {
                include("arm64-v8a", "x86_64")
            }
            isUniversalApk = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
