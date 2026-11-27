// Release properties are supplied by the verified GitHub Actions release job.
val releaseVersionName = providers.gradleProperty("codexReleaseVersionName").orNull
val releaseVersionCode = providers.gradleProperty("codexReleaseVersionCode").orNull?.toIntOrNull()
val releaseTargetAbi = providers.gradleProperty("codexReleaseTargetAbi").orNull
val r8Candidate = providers.gradleProperty("codexR8Candidate").orNull == "true"
require(releaseTargetAbi == null || releaseTargetAbi == "arm64-v8a") {
    "正式发布目标架构只能是 arm64-v8a"
}
val nativeTargetAbi = providers.gradleProperty("codexTargetAbi").orNull ?: releaseTargetAbi
require(nativeTargetAbi == null || nativeTargetAbi in setOf("arm64-v8a", "x86_64")) { "运行目标架构无效" }
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
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.sy220284.codexandroid"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.sy220284.codexandroid"
        minSdk = 29
        targetSdk = 35
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "0.1.0"
        nativeTargetAbi?.takeIf { providers.gradleProperty("codexSplitAbi").orNull != "true" }?.let { ndk { abiFilters += it } }
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
            // Full ARM64 runtime and all 29 UI pages have not yet been validated
            // with R8. Keep the proven release behavior until device coverage exists.
            isMinifyEnabled = r8Candidate
            isShrinkResources = r8Candidate
            if (r8Candidate) proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("../upstream/codex/codex-rs/model-provider/catalogs"))
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libcodex_server.so"
            keepDebugSymbols += "**/libcodex_code_mode_host.so"
            keepDebugSymbols += "**/libcodex_rg.so"
            keepDebugSymbols += "**/libcodex_git.so"
            keepDebugSymbols += "**/libcodex_git_remote_http.so"
            keepDebugSymbols += "**/libcodex_curl.so"
            keepDebugSymbols += "**/libcodex_node.so"
            keepDebugSymbols += "**/libcodex_python.so"
            keepDebugSymbols += "**/libcodex_jq.so"
            keepDebugSymbols += "**/libcodex_tool_launcher.so"
            keepDebugSymbols += "**/libcodex_git_helper_*.so"
        }
    }
    // Enabled only in the native APK pipeline. Standalone UI verification keeps
    // its compact APK and does not require any of the runtime native binaries.
    splits {
        abi {
            isEnable = providers.gradleProperty("codexSplitAbi").orNull == "true"
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // JVM scheduler and flow regression tests require virtual time at the same locked coroutine version.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Stable AndroidX Test release containing the public InputManager lookup.
    // Older Espresso versions crash before Compose assertions on Android 17.
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/** Verify the resolved test runtime, including Espresso pulled in by Compose. */
tasks.register("verifyAndroidTestRuntime") {
    doLast {
        val expected = mapOf(
            "androidx.test:core" to "1.7.0",
            "androidx.test:runner" to "1.7.0",
            "androidx.test:monitor" to "1.8.0",
            "androidx.test.ext:junit" to "1.3.0",
            "androidx.test.espresso:espresso-core" to "3.7.0",
            "androidx.test.espresso:espresso-idling-resource" to "3.7.0"
        )
        val resolved = configurations.getByName("debugAndroidTestRuntimeClasspath")
            .incoming.resolutionResult.allComponents.mapNotNull { component ->
                val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                id?.let { "${it.group}:${it.module}" to it.version }
            }.toMap()
        val report = layout.buildDirectory.file("reports/android-test-dependencies.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(expected.keys.sorted().joinToString("\n", postfix = "\n") {
            "$it:${resolved[it] ?: "未解析"}"
        })
        expected.forEach { (module, version) ->
            check(resolved[module] == version) {
                "Android 测试依赖不匹配：$module，预期 $version，实际 ${resolved[module]}"
            }
        }
    }
}

/** Generate reproducible protocol request fixtures from the same Kotlin unit-test run. */
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val fixtureOutput = layout.buildDirectory.file("protocol-fixtures/requests.json")
    systemProperty("codex.protocolFixturesOutput", fixtureOutput.get().asFile.absolutePath)
    val domesticCatalog = rootProject.file("../upstream/codex/codex-rs/model-provider/catalogs/domestic-providers.json")
    inputs.file(domesticCatalog)
    systemProperty("codex.domesticCatalog", domesticCatalog.absolutePath)
    outputs.file(fixtureOutput)
}

// Lock resolved dependency graphs; baseline creation is an explicit review operation.
dependencyLocking {
    lockAllConfigurations()
    if (file("gradle.lockfile").isFile) lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}
