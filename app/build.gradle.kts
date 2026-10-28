plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "group.talan.photosorter"
    compileSdk = 35

    defaultConfig {
        applicationId = "group.talan.photosorter"
        minSdk = 29          // Android 10+: нужен MediaStore.RELATIVE_PATH
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Фиксированная подпись: каждая сборка на сервере подписана одним и тем же ключом,
    // поэтому новая версия ставится поверх старой без удаления приложения.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Фоновая работа: срабатывание на новые фото + страховочный периодический запуск
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Всё считается на телефоне, фото никуда не уходят
    implementation("com.google.mlkit:text-recognition:16.0.1")   // много текста → документ
    implementation("org.tensorflow:tensorflow-lite:2.16.1")      // своя модель: работа / семья
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
