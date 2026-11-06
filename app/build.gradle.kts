plugins {
    id("com.android.application")
}

android {
    namespace = "com.tim.qzone.autolike"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tim.qzone.autolike"
        minSdk = 21
        targetSdk = 31
        versionCode = 6
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 使用 debug 签名，保证 CI 产出的 APK 可直接安装。
            // 若要长期稳定签名（升级不冲突），请配置自己的 keystore（见 README）。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    // libxposed 新版 API（LSPosed 2.x / API 100+）。仅编译期需要，运行期由框架提供。
    compileOnly("io.github.libxposed:api:102.0.0")
}
