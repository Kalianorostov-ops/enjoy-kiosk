plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ключ подписи берётся из переменных окружения сборки (секреты GitHub).
// Подпись должна быть всегда одна и та же — иначе планшет не примет обновление.
val ksFile = System.getenv("KIOSK_KEYSTORE_FILE")
val ksPass = System.getenv("KIOSK_KEYSTORE_PASSWORD")
val hasKey = !ksFile.isNullOrBlank() && !ksPass.isNullOrBlank() && file(ksFile!!).exists()

android {
    namespace = "ru.enjoythehookah.kiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.enjoythehookah.kiosk"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        if (hasKey) {
            create("enjoy") {
                storeFile = file(ksFile!!)
                storePassword = ksPass
                keyAlias = "enjoy"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKey) signingConfigs.getByName("enjoy") else signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // сканер QR-кода из сервисов Google — без разрешения на камеру
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
