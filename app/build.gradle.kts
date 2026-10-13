plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ibthe.videoencoder"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ibthe.videoencoder"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    flavorDimensions += "dist"
    productFlavors {
        create("direct") { dimension = "dist" }   // раздача файлом, лицензионные ключи
        create("play") { dimension = "dist" }     // Google Play, встроенная покупка
    }
    signingConfigs {
        create("rel") {
            val p = System.getenv("KEYSTORE_FILE")
            if (p != null) {
                storeFile = file(p)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null)
                signingConfigs.getByName("rel") else signingConfigs.getByName("debug")
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    "directImplementation"("org.bouncycastle:bcprov-jdk18on:1.78.1")
    "playImplementation"("com.android.billingclient:billing-ktx:7.1.1")
}
