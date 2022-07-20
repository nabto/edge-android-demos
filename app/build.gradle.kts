plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
}

android {
    compileSdk = 32

    defaultConfig {
        applicationId = "com.nabto.edge.nabtoheatpumpdemo"
        minSdk = 24
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        kapt {
            arguments {
                arg("room.schemaLocation", "$projectDir/schemas")
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
}

dependencies {
    // Android dependencies
    implementation ("androidx.core:core-ktx:1.8.0")
    implementation ("androidx.appcompat:appcompat:1.4.2")
    implementation ("com.google.android.material:material:1.6.1")
    implementation ("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation ("androidx.navigation:navigation-fragment-ktx:2.5.0")
    implementation ("androidx.navigation:navigation-ui-ktx:2.5.0")
    implementation ("androidx.navigation:navigation-dynamic-features-fragment:2.5.0")
    implementation ("androidx.legacy:legacy-support-v4:1.0.0")
    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.5.0")
    implementation ("androidx.lifecycle:lifecycle-livedata-ktx:2.5.0")
    implementation ("com.google.android.material:material:1.6.1")

    // Kotlin dependencies
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.3.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.3")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.3")

    // Koin dependency injection
    val koin_version = "3.2.0"
    implementation ("io.insert-koin:koin-core:$koin_version")
    implementation ("io.insert-koin:koin-android:$koin_version")
    implementation ("io.insert-koin:koin-androidx-workmanager:$koin_version")
    implementation ("io.insert-koin:koin-androidx-navigation:$koin_version")
    testImplementation ("io.insert-koin:koin-test:$koin_version")

    // Nabto dependencies
    implementation ("com.nabto.edge.client:library:kotlin-api-SNAPSHOT")
    implementation ("com.nabto.edge.client:library-ktx:PR-1-SNAPSHOT")
    implementation ("com.nabto.edge.client:iam-util:kotlin-api-SNAPSHOT")

    // Room persistence library to use a database abstracted over sqlite
    val roomVersion = "2.4.2"
    implementation ("androidx.room:room-runtime:$roomVersion")
    kapt ("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    // Test dependencies
    testImplementation ("junit:junit:4.13.2")
    androidTestImplementation ("androidx.test.ext:junit:1.1.3")
    androidTestImplementation ("androidx.test.espresso:espresso-core:3.4.0")
}