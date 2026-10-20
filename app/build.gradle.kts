import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val whiteDnsVersionCode = providers.gradleProperty("WHITE_DNS_VERSION_CODE")
    .map { it.toInt() }
    .orElse(13)
val whiteDnsVersionName = providers.gradleProperty("WHITE_DNS_VERSION_NAME")
    .orElse("1.5.1c")

// Release signing is read from a gitignored keystore.properties at the repo root
// (never committed). Absent the file (e.g. a fresh clone), release builds stay
// unsigned instead of failing, so debug work is unaffected.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()
val cottenDnsNativeBinaries = fileTree("src/main/jniLibs") {
    include("*/libcottendns_client.so")
}
val cottenDnsEngineSources = fileTree(rootProject.file("third_party/CottenDns")) {
    exclude(".gocache/**", "build/**")
}

android {
    namespace = "shop.whitedns.client"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "26.3.11579264"

    defaultConfig {
        // Forked app identity: installs side-by-side with the upstream WhiteDNS
        // (namespace stays shop.whitedns.client so the R class / code package is
        // unchanged; only the install id and FileProvider authority shift).
        applicationId = "shop.whitedns.client.c"
        minSdk = 26
        targetSdk = 34
        versionCode = whiteDnsVersionCode.get()
        versionName = whiteDnsVersionName.get()

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // The app switches English/Persian offline; both languages must stay installed.
    bundle { language { enableSplit = false } }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // These Go executables are already stripped; preserve their source-receipt bytes.
            keepDebugSymbols += setOf("**/libcottendns_client.so", "**/libphantomdns_client.so")
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val verifyCottenDnsNativeBinaries by tasks.registering {
    group = "verification"
    description = "Fails when packaged CottenDns binaries do not match the checked-out engine source."
    inputs.files(cottenDnsNativeBinaries)
    inputs.files(cottenDnsEngineSources)
    inputs.file(rootProject.file("scripts/cottendns-build-manifest.go"))
    inputs.file(rootProject.file("app/cottendns-native-build.json")).optional()

    doLast {
        val repositoryDir = rootProject.projectDir
        fun commandResult(vararg command: String): Pair<Int, String> {
            val process = ProcessBuilder(*command)
                .directory(repositoryDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output.trim()
        }

        // Local source builds use a source-stamped, checksummed receipt. The
        // immutable Git-revision check below remains for old packaged cores.
        if (rootProject.file("app/cottendns-native-build.json").isFile) {
            val (exit, output) = commandResult("go", "run", "scripts/cottendns-build-manifest.go", "verify")
            if (exit != 0) throw GradleException(output)
            logger.lifecycle(output)
            return@doLast
        }

        val (revisionExit, expectedRevision) = commandResult("git", "rev-parse", "HEAD")
        if (revisionExit != 0 || !expectedRevision.matches(Regex("[0-9a-fA-F]{40}"))) {
            throw GradleException("Unable to determine the Git revision for CottenDns binary verification.")
        }

        val (dirtyExit, dirtyOutput) = commandResult(
            "git",
            "diff",
            "--quiet",
            "HEAD",
            "--",
            "third_party/CottenDns",
        )
        if (dirtyExit != 0) {
            throw GradleException(
                "CottenDns engine sources have uncommitted changes. Commit them, then run make CottenDns before building the app." +
                    dirtyOutput.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty(),
            )
        }

        val binaries = cottenDnsNativeBinaries.files.sortedBy { it.path }
        if (binaries.size != 4) {
            throw GradleException("Expected four CottenDns Android binaries, found ${binaries.size}. Run make CottenDns.")
        }

        val revisionPattern = Regex("vcs\\.revision=([0-9a-fA-F]{40})")
        binaries.forEach { binary ->
            val (versionExit, versionOutput) = commandResult("go", "version", "-m", binary.absolutePath)
            val binaryRevision = revisionPattern.find(versionOutput)?.groupValues?.get(1)
            if (versionExit != 0 || !binaryRevision.equals(expectedRevision, ignoreCase = true)) {
                throw GradleException(
                    "Stale CottenDns binary: ${binary.relativeTo(projectDir)}. " +
                        "Expected revision $expectedRevision but found ${binaryRevision ?: "no embedded revision"}. " +
                        "Run make CottenDns.",
                )
            }
        }
    }
}

val verifyPhantomDnsNativeBinaries by tasks.registering {
    group = "verification"
    description = "Checks PhantomDNS source receipts and packaged native binaries."
    inputs.files(fileTree("src/main/jniLibs") { include("**/libphantomdns_client.so") })
    inputs.files(rootProject.fileTree("third_party/PhantomDns") { include("cmd/**", "internal/**", "go.mod", "go.sum") })
    inputs.file(rootProject.file("scripts/cottendns-build-manifest.go"))
    inputs.file(rootProject.file("app/phantomdns-native-build.json")).optional()
    doLast {
        if (rootProject.file("app/phantomdns-native-build.json").isFile) {
            val process = ProcessBuilder("go", "run", "scripts/cottendns-build-manifest.go", "verify", "phantom")
                .directory(rootProject.projectDir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() != 0) throw GradleException(output)
            logger.lifecycle(output.trim())
        }
    }
}

tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("NativeLibs")
}.configureEach {
    dependsOn(verifyCottenDnsNativeBinaries, verifyPhantomDnsNativeBinaries)
}

// Build from the recorded, vendored scanner snapshot. A fresh checkout needs
// no sibling repository; embedded ASN data participates in Gradle invalidation.
val scannerSource = rootProject.projectDir.resolve("third_party/Scanner")
val scannerAar = layout.projectDirectory.file("libs/scanner.aar")
val buildScannerBindings by tasks.registering(Exec::class) {
    inputs.files(fileTree(scannerSource) { include("engine/**", "mobile/**", "go.mod", "go.sum"); exclude("**/*_test.go", "**/testdata/**", "**/README*", "**/.cache/**") })
    outputs.file(scannerAar)
    workingDir(scannerSource)
    doFirst {
        check(scannerSource.resolve("go.mod").isFile) { "The vendored third_party/Scanner module is missing; restore the complete source checkout." }
        scannerAar.asFile.parentFile.mkdirs()
    }
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: Properties().apply { rootProject.file("local.properties").inputStream().use { load(it) } }.getProperty("sdk.dir")
    environment("ANDROID_HOME", sdk)
    environment("ANDROID_NDK_HOME", File(sdk, "ndk/29.0.14206865").absolutePath)
    commandLine("go", "run", "golang.org/x/mobile/cmd/gomobile", "bind", "-target=android", "-androidapi", "26", "-o", scannerAar.asFile.absolutePath, "./mobile")
}
tasks.named("preBuild") { dependsOn(buildScannerBindings) }

dependencies {
    implementation(files("libs/scanner.aar"))
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
