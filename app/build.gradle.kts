plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pddko.overlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pddko.overlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 중국어 OCR (모델 내장, 오프라인)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 중국어→한국어 온디바이스 번역 (모델 최초 1회 다운로드)
    implementation("com.google.mlkit:translate:17.0.3")
}
