plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.deskview"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.deskview"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // CI が初回に生成して commit する固定の debug 用署名鍵。
    // これが無いと実行ごとに署名が変わり、更新インストールが「既存パッケージと競合」で失敗する。
    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file("keystore/deskview-debug.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "deskview"
                keyAlias = "deskview"
                keyPassword = "deskview"
            }
        }
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

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
}
