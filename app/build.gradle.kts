import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ── 发布签名 ──────────────────────────────────────────────────────────────
// keystore.properties 在仓库外/本地，绝不进版本库（.gitignore 已覆盖）；
// CI 上由 workflow 从 Secrets 现场生成同名文件。
// storeFile 相对路径以**根项目**为基准解析，绝对路径原样使用——本地写绝对路径、
// CI 写 release.jks 都能工作。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.biligate.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.biligate.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.11.1"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.service)
    testImplementation("junit:junit:4.13.2")
}

// 没有 keystore.properties 时，assembleRelease 会静默产出**未签名**的 APK——
// 它能构建成功却装不上，是个很能坑人的失败模式。这里让它明确报错。
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any {
        it.name == "assembleRelease" || it.name == "bundleRelease" || it.name == "packageRelease"
    }
    if (wantsRelease && !keystorePropsFile.exists()) {
        throw GradleException(
            "release 构建缺少 keystore.properties —— 拒绝产出未签名（装不上）的包。\n" +
                "  本地：复制 keystore.properties.example 并填好路径与密码。\n" +
                "  CI ：由 .github/workflows/release.yml 从 Secrets 生成。"
        )
    }
}
