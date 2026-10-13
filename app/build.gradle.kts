import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional release signing: keystore.properties in the project root (never committed) with
// storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.revocx35.rwr"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.revocx35.rwr"
        // 29: MediaStore RELATIVE_PATH lets us write to the gallery without storage permissions.
        minSdk = 29
        targetSdk = 37
        versionCode = 5
        versionName = "1.2.0"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            vcsInfo.include = false
        }
    }

    // MediaPipe ships ~14 MB of native code per ABI. Release gets one APK per ABI (arm64-v8a is what
    // nearly every current phone needs) plus a universal one; x86_64 is kept for emulator smoke tests.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // Compress native libraries in the APK: much smaller download, extracted once at install.
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}

// MediaPipe Face Landmarker model (Apache-2.0) for eye tracking. Downloaded once and verified by
// SHA-256 instead of committing a 3.7 MB binary. Same file and hash as server/models.sha256.
val faceModel: File = layout.projectDirectory.file("src/main/assets/face_landmarker.task").asFile
val downloadFaceModel by tasks.registering {
    val out = faceModel
    outputs.file(out)
    doLast {
        val url = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
        val sha256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"
        fun hash(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (out.exists() && hash(out) == sha256) return@doLast
        out.parentFile.mkdirs()
        val tmp = File(out.path + ".part")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val got = hash(tmp)
        if (got != sha256) {
            tmp.delete()
            throw GradleException("face_landmarker.task: sha256 $got != $sha256")
        }
        check(tmp.renameTo(out))
    }
}
tasks.named("preBuild") { dependsOn(downloadFaceModel) }
