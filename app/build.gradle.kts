import java.security.MessageDigest
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Release signing password: the CI secret when one is set, otherwise the password this
 * repository's committed key was generated with, so a plain clone still builds a signed release.
 * A set-but-empty variable must not win over the fallback.
 */
val keystorePassword: String
    get() = System.getenv("OTTER_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "otter123"

android {
    namespace = "com.otter.app"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("../../release.keystore")
            // The CI secret is the real source; the fallback is the password this repository's key
            // was created with, so a plain clone still produces an installable release build.
            storePassword = keystorePassword
            keyAlias = "otter"
            keyPassword = keystorePassword
        }
    }

    defaultConfig {
        applicationId = "com.otter.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "28"
        buildConfigField("String", "BUNDLED_YTDLP_VERSION", "\"2026.08.19\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs["release"]
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Updated Android wrapper: newer yt-dlp bundle + QuickJS runtime for current YouTube extraction.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // Core / lifecycle
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests: the Android platform classes are only stubs there.
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    // Compose UI tests for the on-device clipboard auto-fill behaviour. Test only:
    // neither artifact reaches the release APK.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// The wrapper's native Python/QuickJS/FFmpeg remains unchanged. Bundle a verified stable
// extractor rather than relying on a startup update that is skipped on metered/active sessions.
val bundledEngineRes = layout.buildDirectory.dir("generated/engineRes")
val prepareBundledEngine by tasks.registering {
    val version = "2026.08.19"
    val expected = "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6"
    inputs.property("version", version)
    inputs.property("sha256", expected)
    outputs.dir(bundledEngineRes)
    doLast {
        val output = bundledEngineRes.get().file("raw/outter_ytdlp").asFile
        output.parentFile.mkdirs()
        val temporary = File(output.parentFile, "engine.tmp")
        try {
            val connection = URL("https://github.com/yt-dlp/yt-dlp/releases/download/$version/yt-dlp").openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            val actual = MessageDigest.getInstance("SHA-256").digest(temporary.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Bundled yt-dlp checksum mismatch" }
            java.util.zip.ZipFile(temporary).use { zip ->
                check(zip.getEntry("yt_dlp_ejs/yt/solver/lib.min.js") != null) { "Bundled engine is missing the YouTube EJS solver" }
            }
            check(temporary.renameTo(output)) { "Cannot stage bundled yt-dlp" }
        } finally { temporary.delete() }
    }
}
android.sourceSets["main"].res.srcDir(bundledEngineRes)
tasks.named("preBuild").configure { dependsOn(prepareBundledEngine) }
