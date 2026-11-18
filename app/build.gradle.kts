plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.cameracompositor"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.cameracompositor"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.0-camera"
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(rootProject.file(".local/software-depth-assets"))
    if (providers.gradleProperty("includeLegacyResearch").orNull == "true") {
        sourceSets.getByName("debug").assets.srcDir(rootProject.file(".local/research-assets"))
    }
    androidResources { noCompress += "onnx" }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.google.ar:core:1.57.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-extensions:$cameraX")
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
