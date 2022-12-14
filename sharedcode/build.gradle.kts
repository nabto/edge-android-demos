plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
}

android {
    compileSdk = 33

    defaultConfig {
        minSdk = 24
        targetSdk = 32

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    kapt {
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas")
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
    api ("androidx.core:core-ktx:1.9.0")
    api ("androidx.appcompat:appcompat:1.5.1")
    api ("com.google.android.material:material:1.7.0")
    api ("androidx.constraintlayout:constraintlayout:2.1.4")
    api ("androidx.navigation:navigation-fragment-ktx:2.5.3")
    api ("androidx.navigation:navigation-ui-ktx:2.5.3")
    api ("androidx.navigation:navigation-dynamic-features-fragment:2.5.3")
    api ("androidx.legacy:legacy-support-v4:1.0.0")
    api ("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    api ("androidx.lifecycle:lifecycle-livedata-ktx:2.5.1")
    api ("com.google.android.gms:play-services-vision:20.1.3")
    api ("androidx.preference:preference-ktx:1.2.0")

    // Nabto dependencies
    val nabtoVersion = "master-SNAPSHOT"
    api ("com.nabto.edge.client:library:$nabtoVersion")
    api ("com.nabto.edge.client:library-ktx:$nabtoVersion")
    api ("com.nabto.edge.client:iam-util:$nabtoVersion")
    api ("com.nabto.edge.client:iam-util-ktx:$nabtoVersion")

    // Kotlin dependencies
    api ("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    api ("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.3.2")
    api ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    api ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    api ("androidx.annotation:annotation:1.4.0")
    api ("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    api ("androidx.lifecycle:lifecycle-process:2.5.1")

    // Room persistence library to use a database abstracted over sqlite
    val roomVersion = "2.4.2"
    api ("androidx.room:room-runtime:$roomVersion")
    kapt ("androidx.room:room-compiler:$roomVersion")
    api ("androidx.room:room-ktx:$roomVersion")

    // Koin dependency injection
    val koinVersion = "3.2.0"
    api ("io.insert-koin:koin-core:$koinVersion")
    api ("io.insert-koin:koin-android:$koinVersion")
    api ("io.insert-koin:koin-androidx-workmanager:$koinVersion")
    api ("io.insert-koin:koin-androidx-navigation:$koinVersion")
    testImplementation ("io.insert-koin:koin-test:$koinVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}