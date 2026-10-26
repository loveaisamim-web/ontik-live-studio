plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ontik.livestudio"
    compileSdk = 35

    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
        }
    }

    defaultConfig {
        applicationId = "com.ontik.livestudio"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.5")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.google.android.gms:play-services-auth:21.3.0")

    implementation("com.google.apis:google-api-services-youtube:v3-rev20241010-2.0.0") {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.http-client:google-http-client-android:1.42.2")
    implementation("com.google.http-client:google-http-client-gson:1.42.2")
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
