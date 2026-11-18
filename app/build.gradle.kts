import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val personDetectorModelName = "efficientdet_lite0_int8.tflite"
val personDetectorModelUrl =
    "https://storage.googleapis.com/mediapipe-models/object_detector/" +
        "efficientdet_lite0/int8/1/efficientdet_lite0.tflite"
val personDetectorModelSha256 =
    "0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb"
val generatedPersonModelDir = layout.buildDirectory.dir("generated/person-model")

val downloadPersonDetectorModel by tasks.registering {
    val outputFile = generatedPersonModelDir.map { it.file(personDetectorModelName) }
    inputs.property("modelUrl", personDetectorModelUrl)
    inputs.property("modelSha256", personDetectorModelSha256)
    outputs.file(outputFile)
    doLast {
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        val temporary = destination.resolveSibling("${destination.name}.download")
        URI(personDetectorModelUrl).toURL().openStream().use { input ->
            temporary.outputStream().use(input::copyTo)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(temporary.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == personDetectorModelSha256) {
            "Unexpected EfficientDet-Lite0 checksum: $digest"
        }
        temporary.copyTo(destination, overwrite = true)
        check(temporary.delete()) { "Unable to remove temporary model download" }
    }
}

android {
    namespace = "com.xtark.pilot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xtark.pilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.9.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(generatedPersonModelDir)

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
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("com.google.android.material:material:1.14.0")

    val cameraXVersion = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

tasks.named("preBuild").configure {
    dependsOn(downloadPersonDetectorModel)
}
