plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "top.littlew.acer"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.littlew.acer"
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
