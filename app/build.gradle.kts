plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gmssh.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gmssh.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 20600
        versionName = "2.0.6"
        // 只保留通用 ABI 无关的纯 Java/Kotlin 代码，无需 ndk 配置
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 使用 debug 签名，方便侧载安装
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
