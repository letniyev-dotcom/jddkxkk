import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Подпись release-сборки. Если GitHub Actions передал свои секреты (LETNIE_KEYSTORE...), используются они,
// иначе — постоянный ключ из app/signing/letnie.jks. Ключ один и тот же в каждой сборке,
// поэтому новая версия ставится поверх старой без удаления приложения.
val keystorePath: String = System.getenv("LETNIE_KEYSTORE") ?: "signing/letnie.jks"
val keystorePass: String = System.getenv("LETNIE_KEYSTORE_PASSWORD") ?: "letnie-release"
val keyAliasName: String = System.getenv("LETNIE_KEY_ALIAS") ?: "letnie"
val keyPass: String = System.getenv("LETNIE_KEY_PASSWORD") ?: keystorePass

// versionCode обязан расти с каждой сборкой, иначе Android не обновит приложение: берём номер запуска CI
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.letnievpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.letnievpn"
        minSdk = 24
        targetSdk = 35
        versionCode = 100 + buildNumber
        versionName = "1.1.$buildNumber"
    }

    // Один универсальный APK + отдельные по архитектурам (arm64 — самый лёгкий)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystorePath)
            storePassword = keystorePass
            keyAlias = keyAliasName
            keyPassword = keyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
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
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Debug-сборки не нужны: отключаем debug-вариант полностью
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variantBuilder ->
        variantBuilder.enable = false
    }
}

dependencies {
    // ядро xray (libv2ray.aar) кладёт в app/libs GitHub Actions
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
