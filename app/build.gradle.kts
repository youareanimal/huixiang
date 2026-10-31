import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// This machine's own settings (dev.env, never committed): where debug builds send their log.
val devEnv: Map<String, String> = rootProject.file("dev.env").takeIf { it.exists() }?.readLines()
    ?.mapNotNull { line -> line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") && "=" in it } }
    ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    ?: emptyMap()

val updateHost = System.getenv("HUIXIANG_UPDATE_HOST")?.takeIf { it.isNotBlank() }
    ?: "updates.example.invalid"
require(updateHost.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"))) {
    "Invalid update host configuration"
}

android {
    namespace = "io.github.korppujauho.pocketts"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.shengqiao.ts"
        minSdk = 29
        targetSdk = 36
        versionCode = 17
        versionName = "0.5.4"
        buildConfigField("String", "UPDATE_FEED_URL", "\"https://${updateHost}:8443/huixiang/latest.json\"")
        buildConfigField("String", "UPDATE_FALLBACK_FEED_URL", "\"https://huixiang-updates.pages.dev/latest.json\"")
        // Phones (WebRTC's native code is large; these two cover practically every phone)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // The release key: its file and passwords come from scripts/dev.sh release (asked each time), never from git
    val releaseKey = System.getenv("POCKETTS_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey
                storePassword = System.getenv("POCKETTS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POCKETTS_KEY_ALIAS") ?: "pocketts"
                keyPassword = System.getenv("POCKETTS_KEY_PASSWORD") ?: System.getenv("POCKETTS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "DEV_LOG_URL", "\"${devEnv["DEV_LOG_URL"].orEmpty()}\"")
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
        release {
            // Smaller and faster: unused code and resources left out (proguard-rules.pro keeps what WebRTC needs)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "DEV_LOG_URL", "\"\"")
            // The GitHub release asks GitHub for newer versions (a Google Play build won't)
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
        // The Google Play version: the release as an app bundle (bundlePlay), the same app and key, but without the
        // GitHub update check (Play updates it)
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
        // The release, shrunk the same way, but signed with the test key and sending its log like a test build:
        // to find what shrinking broke (scripts/dev.sh apk-shrunk). An app of its own (".shrunk").
        create("shrunk") {
            initWith(getByName("release"))
            applicationIdSuffix = ".shrunk"
            versionNameSuffix = "-shrunk"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "DEV_LOG_URL", "\"${devEnv["DEV_LOG_URL"].orEmpty()}\"")
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
        // Release shrinking, signed with the local test key. Updates the previous debug package in place.
        create("compact") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-preview"
            // CI builds the APK; the existing upgrade key stays on the release machine.
            signingConfig = if (System.getenv("HUIXIANG_UNSIGNED_BUILD") == "1") null else signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // The app's languages, listed for Android 13 and later (Settings › Apps › PocketTS › Language), from res/values-*
    androidResources {
        generateLocaleConfig = true
    }

    // APKs distributed outside Google Play can't use the store's ABI delivery.
    // Keep a universal package and add two smaller APKs so a phone downloads
    // only the WebRTC native code its CPU needs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // Every language in every install from Google Play: the app's language can be chosen in its settings
    bundle {
        language { enableSplit = false }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":tsproto"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    // Pictures: avatars, the server's icons (mostly SVG on TeamSpeak 6), pictures in the chat (also animated)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network)
    // Screen and camera streams (TeamSpeak 6's are WebRTC)
    implementation(libs.webrtc)

    // Screenshots of the screens, made on the build machine (scripts/dev.sh screenshots)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(rootProject.file("docs/screenshots"))
}

tasks.withType<Test>().configureEach {
    // Robolectric reaches into Java internals that Java 21 keeps closed by default
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}
