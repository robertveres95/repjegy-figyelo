plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "hu.repjegy.figyelo"
    compileSdk = 36

    defaultConfig {
        applicationId = "hu.repjegy.figyelo"
        minSdk = 28
        targetSdk = 35
        // versionName: a version.properties-ből (pl. 1.1.0) – ezt látja a felhasználó.
        // versionCode: a GitHub Actions futásszáma, mindig nő, így a frissítés a régi fölé települ.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = rootProject.file("version.properties").readLines()
            .first { it.startsWith("VERSION_NAME=") }
            .substringAfter("=").trim()
    }

    signingConfigs {
        // Fix kulcs, hogy az új verziók a régi fölé települjenek (adatvesztés nélkül).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // A kiadott APK aláírását a GitHub Actions végzi (kulcscserével, lásd build.yml)
            isMinifyEnabled = false
        }
    }

    sourceSets {
        // A közös (Android + Windows) kód
        getByName("main").java.srcDir("../shared/src/main/kotlin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Statikus elemzés (gradle :app:lintDebug) – a hibakereső folyamat használja
    lint {
        abortOnError = false
        textReport = true
        checkDependencies = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    // Google-bejelentkezés a szinkronizáláshoz (csak a Drive alkalmazásadat-területe)
    implementation("com.google.android.gms:play-services-auth:22.0.0")
}
