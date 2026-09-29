plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tv.danmaku.bili"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.danmaku.bili"   // 仿真包名：让 BiliGate 无障碍服务把它当B站
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-fake"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
