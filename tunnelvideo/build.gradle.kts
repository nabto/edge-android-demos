import java.util.Properties
import java.io.FileInputStream

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
                val properties = Properties()
                properties.load(project.rootProject.file("local.properties").inputStream())

                val propertiesKey = "gstAndroidRoot"
                val envKey = "GSTREAMER_ROOT_ANDROID"
                val gstRoot: String? =
                    if (project.properties.containsKey(propertiesKey)) {
                        project.properties[propertiesKey] as String
                    } else if (properties.containsKey(propertiesKey)) {
                        properties[propertiesKey] as String
                    } else {
                        System.getenv()[envKey]
                    }

                gstRoot?.let {
                    val main = "src/main"
                    arguments("NDK_APPLICATION_MK=$main/jni/Application.mk", "GSTREAMER_JAVA_SRC_DIR=$main/java", "GSTREAMER_ROOT_ANDROID=$gstRoot", "GSTREAMER_ASSETS_DIR=$main/assets")
                    targets("tunnel-video")
                    abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                } ?: run {
                    throw GradleException("$envKey must be set, or define $propertiesKey in your local.properties or gradle.properties")
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
    implementation("com.google.android.exoplayer:exoplayer-core:2.18.2")

    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.0")
    implementation("com.google.android.material:material:1.8.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}