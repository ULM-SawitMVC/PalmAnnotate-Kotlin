import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ── Version config ──────────────────────────────────────────────────────
// MAJOR.MINOR is manual — bump when you have a meaningful feature set.
// PATCH is auto (git commit count) — every commit increments it.
val majorMinor = "0.3"   // ← change this for new releases
val commitCount = run {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        standardOutput = stdout
    }
    stdout.toString().trim().toIntOrNull() ?: 1
}
val phaseAci = providers.gradleProperty("phaseAci").map(String::toBoolean).orElse(false).get()

// ── WS-21: persistent distribution signing identity ─────────────────────────
// A runner-generated debug keystore is a DIFFERENT signer on every CI machine. An APK signed
// on runner B can never `install -r` over the APK from runner A: the operator would have to
// uninstall first, which deletes that package's app-private dataset. These secrets pin ONE
// durable signer for the distributable variants (`field`, `trace`).
//
// Secret / Gradle-property names (see SigningIdentityPolicy.REQUIRED_SECRETS — the JVM test
// `SigningIdentityPolicyTests` fails if these two lists ever drift apart):
//   PALMANNOTATE_KEYSTORE_BASE64    base64 of the .jks   (CI)
//   PALMANNOTATE_KEYSTORE_PATH      path to the .jks     (local alternative to _BASE64)
//   PALMANNOTATE_KEYSTORE_PASSWORD  store password
//   PALMANNOTATE_KEY_ALIAS          key alias
//   PALMANNOTATE_KEY_PASSWORD       key password
//
// When they are absent the build STILL SUCCEEDS (local development needs no secrets) but falls
// back to the debug keystore and stamps every distributable APK `-NOKEY`, so an ephemeral
// identity can never be mistaken for a distributable artifact. `release.yml` refuses to publish
// in that state; see CLAUDE.md "Distribution signing".
fun signingSecret(name: String): String? =
    (System.getenv(name) ?: providers.gradleProperty(name).orNull)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val distStorePassword = signingSecret("PALMANNOTATE_KEYSTORE_PASSWORD")
val distKeyAlias = signingSecret("PALMANNOTATE_KEY_ALIAS")
val distKeyPassword = signingSecret("PALMANNOTATE_KEY_PASSWORD")
val distKeystoreFile: File? = run {
    val base64 = signingSecret("PALMANNOTATE_KEYSTORE_BASE64")
    val path = signingSecret("PALMANNOTATE_KEYSTORE_PATH")
    // Materialise the blob only when the passwords/alias are present too. Without them no
    // signingConfig is created, so decoding a private key onto disk would achieve nothing except
    // leaving it there.
    val credentialsPresent =
        distStorePassword != null && distKeyAlias != null && distKeyPassword != null
    when {
        base64 != null && credentialsPresent -> {
            // Fail loudly: a corrupt secret must never silently degrade to the debug keystore,
            // because that produces an APK that looks distributable but cannot update anything.
            val bytes = try {
                Base64.getDecoder().decode(base64.replace(Regex("\\s"), ""))
            } catch (e: IllegalArgumentException) {
                throw GradleException(
                    "PALMANNOTATE_KEYSTORE_BASE64 is not valid base64; refusing to fall back " +
                        "to an ephemeral debug signer for a distribution build.",
                    e,
                )
            }
            File(layout.buildDirectory.get().asFile, "tmp/signing/palmannotate-distribution.jks")
                .apply {
                    parentFile.mkdirs()
                    writeBytes(bytes)
                    // Owner-only. The file lives under the gitignored build directory, but it is
                    // still an unencrypted private key sitting in the project tree.
                    setReadable(false, false)
                    setReadable(true, true)
                    setWritable(false, false)
                    setWritable(true, true)
                    // Deliberately NOT deleteOnExit(): AGP reads storeFile during packaging, and
                    // a configuration-cache hit would skip this block entirely and then find the
                    // file gone. `gradlew clean` removes it with the rest of build/.
                }
        }
        path != null && credentialsPresent -> File(path).takeIf { it.isFile }
            ?: throw GradleException("PALMANNOTATE_KEYSTORE_PATH does not point at a file: $path")
        else -> null
    }
}
val hasDistributionSigning = distKeystoreFile != null &&
    distStorePassword != null && distKeyAlias != null && distKeyPassword != null
/** Mirrors SigningIdentityPolicy.PERSISTENT / .EPHEMERAL_DEBUG. */
val signingIdentity = if (hasDistributionSigning) "PERSISTENT" else "EPHEMERAL_DEBUG"
/** Variants that are handed to an operator and therefore must carry a stable update identity. */
val distributableVariants = setOf("field", "trace")

android {
    namespace = "dev.sawitulm.palmannotate"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.sawitulm.palmannotate"
        minSdk = 24
        targetSdk = 34
        versionCode = commitCount
        versionName = "$majorMinor.$commitCount"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // WS-21: the app can state its own update identity at runtime (see PalmAnnotateApp).
        buildConfigField("String", "SIGNING_IDENTITY", "\"$signingIdentity\"")

        ndk {
            // Production and field builds are arm64-only for the Orbbec SDK. Phase-A CI opts into
            // x86_64 because the hosted emulator cannot execute arm64-only APKs; its tests never
            // touch the native camera path.
            abiFilters += if (phaseAci) listOf("x86_64") else listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Created only when every secret resolved. Absent = local/unsecreted build, which keeps
        // working on the debug signer (and is marked -NOKEY below).
        if (hasDistributionSigning) {
            create("distribution") {
                storeFile = distKeystoreFile
                storePassword = distStorePassword
                keyAlias = distKeyAlias
                keyPassword = distKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // ORB-09: R8 minify/shrink MUST stay OFF — it strips something the Orbbec SDK reaches
            // via JNI/reflection and the live depth preview freezes ("one frame then freeze").
            // See CLAUDE.md "R8 Minification — DO NOT ENABLE". Re-enable only after re-verifying
            // the live Orbbec preview on a physical device.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        create("trace") {
            initWith(getByName("debug"))
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".trace"
            // WS-21: trace is a distributed diagnostic app, so it needs the same durable update
            // identity as field — otherwise replacing it also forces an uninstall.
            signingConfig = signingConfigs.getByName(
                if (hasDistributionSigning) "distribution" else "debug",
            )
            matchingFallbacks += listOf("debug")
        }
        create("field") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            // WS-21: persistent signer when the secrets are configured; debug keystore otherwise
            // (local builds keep working, and the APK is renamed -NOKEY so it cannot be mistaken
            // for a distributable artifact).
            signingConfig = signingConfigs.getByName(
                if (hasDistributionSigning) "distribution" else "debug",
            )
            applicationIdSuffix = ".field"
            matchingFallbacks += listOf("release", "debug")
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

    // MigrationTestHelper loads the exported schemas from the *test APK's assets*, not from the
    // project directory. Without this the migration tests fail on device with
    // "Cannot find the schema file in the assets folder ... Missing file: <db>/3.json"
    // even though app/schemas holds every version.
    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // The Orbbec AAR ships its extension .so files under assets/ (not jniLibs),
        // so abiFilters does NOT strip them. Keep only the assets for the selected ABI.
        ignoreAssetsPatterns += "armeabi-v7a"
        if (phaseAci) ignoreAssetsPatterns += "arm64-v8a"
    }

    // Output APK as PalmAnnotate-<buildtype>-v<version>.apk
    // e.g. PalmAnnotate-debug-v0.1.apk
    //
    // WS-21: a distributable variant built without the signing secrets gets a `-NOKEY` marker.
    // That APK installs and runs, but it cannot update (or be updated by) any APK signed with
    // the real key, so the name has to say so at a glance. `release.yml` fails before building
    // when the secrets are missing, so a Release asset can never carry this marker.
    applicationVariants.all {
        val variant = this
        val identityMarker =
            if (!hasDistributionSigning && variant.name in distributableVariants) "-NOKEY" else ""
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName =
                "PalmAnnotate-${variant.name}-v${variant.versionName}$identityMarker.apk"
        }
    }
}

ksp {
    // Room records the schema on disk when exportSchema=true, so explicit migrations can be
    // validated against it. Enables safe, NON-destructive migrations — do NOT remove.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // CameraX
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // ONNX Runtime
    implementation(libs.onnxruntime.android)

    // Orbbec USB RGB-D SDK (vendored Android wrapper v2.0.6).
    // The AAR has one Android 13+ receiver-flag patch documented in app/libs/patches.
    // Native libraries and public SDK APIs are unchanged.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Coil (image loading)
    implementation(libs.coil.compose)

    // Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Google Play Services (location)
    implementation(libs.play.services.location)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Real org.json for unit tests — the android.jar bundled at unit-test time stubs
    // org.json to throw "Stub!", so ExportManager's JSONObject/JSONArray output can't be
    // asserted without a real implementation on the test classpath.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
