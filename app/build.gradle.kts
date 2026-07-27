plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Run a git command in the project dir; null on any failure (no git binary, no
 * `.git` — e.g. a source tarball). Never fails the build: the version falls back
 * to the [BASE_VERSION] constants below.
 */
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
    }.standardOutput.asText.get().trim().ifBlank { null }
}.getOrNull()

// Marketing version. Bump by hand for a real release; the patch component and the
// build metadata below are derived so every build is identifiable on sight.
val baseVersion = "0.2"

// versionCode = commits on the current branch. master is linear, so this only ever
// increases — which is what Android requires for in-place upgrades.
val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

// versionName = 0.2.<commits>+<sha>[.dirty]. This is what the Hearth wire protocol
// reports to HA as each device's sw_version, so an at-a-glance look at the HA device
// page tells you exactly which build is on which device — and `.dirty` flags a build
// flashed from an uncommitted tree.
val shortSha = git("rev-parse", "--short", "HEAD") ?: "nogit"
val dirty = if (git("status", "--porcelain")?.isNotEmpty() == true) ".dirty" else ""

// Signing. Every build stays DEBUGGABLE on purpose -- `run-as com.rar.echodash` is how
// wake captures and app-private files come off the devices, and a non-debuggable build
// would take that away. What the keystore below buys is a *stable* signature: without it
// Gradle mints a throwaway debug key per machine (and a fresh one on every CI runner), so
// no build could ever install over another. Android requires a matching signature to
// update in place, which is what makes the in-app updater possible at all.
//
// Resolution order, first hit wins:
//   1. HEARTH_KEYSTORE / HEARTH_KEYSTORE_PASSWORD env vars  (CI, from repo secrets)
//   2. ~/.hearth/hearth-release.jks + keystore-password.txt (this dev box)
//   3. nothing -> Gradle's default debug key                (fresh clone, PR builds)
// Case 3 still builds and still runs; its output simply cannot update a device in place.
val keystoreFile: File? = (System.getenv("HEARTH_KEYSTORE")?.let { File(it) }
    ?: File(System.getProperty("user.home"), ".hearth/hearth-release.jks"))
    .takeIf { it.isFile }
val keystorePassword: String? = System.getenv("HEARTH_KEYSTORE_PASSWORD")
    ?: File(System.getProperty("user.home"), ".hearth/keystore-password.txt")
        .takeIf { it.isFile }?.readText()?.trim()

android {
    namespace = "com.rar.hearth"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.rar.echodash"
        minSdk = 27
        targetSdk = 34
        versionCode = commitCount
        versionName = "$baseVersion.$commitCount+$shortSha$dirty"
    }
    signingConfigs {
        // Overrides the default debug key only when the keystore actually resolved;
        // otherwise the debug build type keeps Gradle's generated one (case 3 above).
        if (keystoreFile != null && keystorePassword != null) {
            getByName("debug") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "hearth"
                keyPassword = keystorePassword
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Lint is the only automated check on the Android-framework surface: the test
        // policy is plain-JVM JUnit4 (no instrumented/Robolectric tests), so nothing
        // else catches NewApi calls below the minSdk 27 floor or manifest/resource
        // problems. Keep it failing the build in CI.
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = false
        sarifReport = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.ktor:ktor-client-core:3.1.1")
    implementation("io.ktor:ktor-client-websockets:3.1.1")
    implementation("io.ktor:ktor-client-okhttp:3.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
