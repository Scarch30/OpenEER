plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.openeer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.openeer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Optionnel : limiter les ABIs pour réduire la taille
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // ➜ Les flags C++ pour CMake se mettent ici
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // ⚠️ hack local: signe la release avec la clé debug
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    // ⚙️ Tests JVM (Robolectric a besoin des ressources)
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // ➜ Câblage CMake (chemin du CMakeLists de Whisper)
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
            // version = "3.22.1" // optionnel
        }
    }

    // Evite quelques conflits META-INF
    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")

    // --- Room ---
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Vosk + JNA (inchangé)
    implementation("com.alphacephei:vosk-android:0.3.45") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // --- Glide ---
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // --- CameraX 1.5.0 ---
    val camerax = "1.5.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")

    // Media3
    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // MapLibre GL Android (API v12, simple et stable)
    implementation("org.maplibre.gl:android-sdk:12.0.0")
    implementation("org.maplibre.gl:android-plugin-annotation-v9:3.0.2")

    implementation("com.google.code.gson:gson:2.11.0")

    implementation("io.getstream:photoview:1.0.2")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.fragment:fragment-testing:1.8.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:$room")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
