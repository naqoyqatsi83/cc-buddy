import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ccbuddy.app"
    compileSdk = 34

    // CI passes these for a tagged release build (-PreleaseVersionName=X.Y.Z
    // -PreleaseVersionCode=<n>, derived from the git tag and the workflow
    // run number -- see .github/workflows/build-apk.yml) so the APK's own
    // version actually reflects what was tagged instead of staying frozen
    // at whatever it was when the app was first scaffolded. Local/dev
    // builds fall back to these defaults untouched.
    val ciVersionName = (project.findProperty("releaseVersionName") as String?)
    val ciVersionCode = (project.findProperty("releaseVersionCode") as String?)?.toIntOrNull()

    // Local/dev builds have no CI-provided versionCode, so they always
    // showed a hardcoded "build 1" in the app's own UI (AsciiLogo.kt)
    // regardless of how many times the APK was actually rebuilt --
    // useless for telling two local debug builds apart. This persists a
    // counter (gitignored -- it's a local dev artifact, not meaningful
    // across machines/checkouts, same reasoning as build/ or .gradle/)
    // and bumps it once per Gradle invocation, so each local build gets
    // a real, distinct, increasing number. Only used as the *fallback*;
    // CI-tagged release builds still get their real versionCode from the
    // workflow as before.
    val localBuildNumberFile = file("build-number.properties")
    val localBuildNumber = run {
        val props = Properties()
        if (localBuildNumberFile.exists()) {
            localBuildNumberFile.inputStream().use { props.load(it) }
        }
        val next = (props.getProperty("buildNumber")?.toIntOrNull() ?: 0) + 1
        props.setProperty("buildNumber", next.toString())
        localBuildNumberFile.outputStream().use {
            props.store(it, "Local debug build counter -- auto-incremented on every Gradle build, not meaningful for CI release builds")
        }
        next
    }

    // CI provides these via env vars (see .github/workflows/build-apk.yml) so
    // the public release APK is signed with a real, stable key instead of
    // gradle's ephemeral debug key -- required for users to receive future
    // updates as upgrades rather than "uninstall and reinstall". Absent
    // locally, `release` builds fall back to unsigned (fine for local
    // testing; only CI's tagged builds need to actually install on a phone
    // as an update).
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")

    defaultConfig {
        applicationId = "dev.ccbuddy.app"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode ?: localBuildNumber
        versionName = ciVersionName ?: "0.3.0"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Netty and BouncyCastle each ship several jars ("netty-*",
            // "bc{prov,util,pkix}-jdk18on") that duplicate the same
            // MET-INF housekeeping files (index/version metadata, license
            // text, OSGi manifests) -- Android's resource merger treats
            // any duplicate as an error, so drop these non-code files
            // broadly rather than excluding them one at a time.
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/versions/9/OSGI-INF/**"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    val ktorVersion = "2.3.12"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    // Netty, not CIO: CIO's server engine has no SSL/TLS support
    // (ktorio/ktor#886 is still open), and the WS server needs to speak
    // wss:// to the daemon.
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")

    // For generating the phone's self-signed TLS identity -- the JVM's own
    // X.509 cert-builder APIs are internal sun.security.x509 classes,
    // unreliable/restricted on Android.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
