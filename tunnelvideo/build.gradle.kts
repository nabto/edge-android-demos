import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.argumentsWithVarargAsSingleArray

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlinx-serialization")
}

android {
    compileSdk = 33

    defaultConfig {
        applicationId = "com.nabto.edge.tunnelvideodemo"
        minSdk = 26
        targetSdk = 33
        versionCode = 13
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            ndkBuild {
                val gstRoot: String? =
                    if (project.hasProperty("gstAndroidRoot")) {
                        project.property("gstAndroidRoot") as String
                    } else {
                        System.getenv()["GSTREAMER_ROOT_ANDROID"]
                    }

                gstRoot?.let {
                    arguments("NDK_APPLICATION_MK=src/main/jni/Application.mk", "GSTREAMER_JAVA_SRC_DIR=src/main/java", "GSTREAMER_ROOT_ANDROID=$gstRoot", "GSTREAMER_ASSETS_DIR=src/main/assets")
                    targets("tunnel-video")
                    abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                } ?: run {
                    throw GradleException("GSTREAMER_ROOT_ANDROID must be set, or define gstAndroidRoot in your gradle.properties")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
    }
    externalNativeBuild {
        ndkBuild {
            path ("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    // Dependencies are all pulled from sharedcode module
    implementation (project(mapOf("path" to ":sharedcode")))

    // Exoplayer
    implementation ("com.google.android.exoplayer:exoplayer:2.18.1")

    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("com.google.android.material:material:1.6.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}