import java.util.Properties

plugins {
    // AGP 9 built-in Kotlin: never apply org.jetbrains.kotlin.android here.
    id("com.android.application")
}

// Release signing comes from keystore.properties at the repo root, which names the keystore,
// its alias and its passwords. Without that file the project still builds; the release APK
// just comes out unsigned.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.kaiwa.chat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kaiwa.chat"
        // Target device: KYOCERA NP902KC, Android 8.1 (API 27).
        minSdk = 27
        targetSdk = 36
        versionCode = 10
        versionName = "1.9"
    }

    // Only created when keystore.properties is present, so a fresh clone is not blocked.
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // null when keystore.properties is absent, which leaves the APK unsigned
            // rather than failing the build.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        // For BuildConfig.VERSION_NAME: the About section reads it rather than repeating it.
        buildConfig = true
    }
}

// No networking or JSON libraries on purpose: HttpURLConnection and org.json are
// already on the device. Keeps the APK small and memory use low on a 1 GB phone.
dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
}
