plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linksync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linksync.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDirs("src/main/assets", "../../shared/www")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // zxing-android-embedded 4.3.0 的 POM 只带 com.google.zxing:core，
    // androidx.core / fragment 是 compileOnly，必须在应用侧显式引入，
    // 否则 CaptureManager.onResume() 会抛 NoClassDefFoundError 直接闪退。
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}
