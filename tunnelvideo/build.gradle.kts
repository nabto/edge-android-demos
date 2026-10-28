import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlinx-serialization")
}

val gstPropertiesKey = "gstAndroidRoot"
val gstEnvKey = "GSTREAMER_ROOT_ANDROID"

val gstRoot: String? = run {
    val localProperties = project.rootProject.file("local.properties")
    val properties =
        if (localProperties.exists()) {
            val props = Properties()
            props.load(localProperties.inputStream())
            props
        } else {
            null
        }

    if (project.properties.containsKey(gstPropertiesKey)) {
        project.properties[gstPropertiesKey] as String
    } else if (properties != null && properties.containsKey(gstPropertiesKey)) {
        properties[gstPropertiesKey] as String
    } else {
        System.getenv()[gstEnvKey]
    }
}

// Only fail when tunnelvideo itself is built, so other apps in the project
// can be built without GStreamer being set up.
if (gstRoot == null) {
    tasks.named("preBuild") {
        doFirst {
            throw GradleException("$gstEnvKey must be set, or define $gstPropertiesKey in your local.properties or gradle.properties")
        }
    }
}

android {
    compileSdk = 34
    namespace = "com.nabto.edge.tunnelvideodemo"
    ndkVersion = "20.1.5948944"

    defaultConfig {
        applicationId = "com.nabto.edge.tunnelvideodemo"
        minSdk = 28
        versionCode = 20
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (gstRoot != null) {
            externalNativeBuild {
                ndkBuild {
                    val main = "src/main"
                    arguments("NDK_APPLICATION_MK=$main/jni/Application.mk", "GSTREAMER_JAVA_SRC_DIR=$main/java", "GSTREAMER_ROOT_ANDROID=$gstRoot", "GSTREAMER_ASSETS_DIR=$main/assets")
                    targets("tunnel-video")
                    abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
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
    if (gstRoot != null) {
        externalNativeBuild {
            ndkBuild {
                path ("src/main/jni/Android.mk")
            }
        }
    }
}

dependencies {
    // Dependencies are all pulled from sharedcode module
    implementation (project(mapOf("path" to ":sharedcode")))
    implementation(libs.exoplayer.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}