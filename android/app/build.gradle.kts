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

    defaultConfig {
        applicationId = "dev.ccbuddy.app"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1.0"
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
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
