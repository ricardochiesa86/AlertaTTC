plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alertattc.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alertattc.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-skeleton"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // O .tflite vai em src/main/assets sem compressao, senao o AAPT
    // corrompe o arquivo binario ao empacotar.
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // Ciclo de vida + servico em primeiro plano com CameraX
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // CameraX: 1.3+ e necessario para ImageAnalysis em RGBA_8888 direto,
    // evitando conversao manual de YUV.
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")

    // LiteRT (sucessor do TensorFlow Lite). Mantem os pacotes
    // org.tensorflow.lite.* para compatibilidade. litert-gpu traz o
    // delegate de GPU (Mali via OpenGL/OpenCL, driver-dependente).
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.1")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
