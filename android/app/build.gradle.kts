plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.budget.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.budget.app"
        minSdk = 26
        targetSdk = 35
        // versionCode берётся из переменной окружения в GitHub Actions (номер сборки),
        // чтобы каждая новая сборка ставилась поверх предыдущей как обновление
        versionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/budget.jks")
            storePassword = "budget123"
            keyAlias = "budget"
            keyPassword = "budget123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
