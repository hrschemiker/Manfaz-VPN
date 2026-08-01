import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val signingProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }
        ?.inputStream()?.use { load(it) }
}
val hasReleaseSigning = listOf(
    "manfaz.storeFile", "manfaz.storePassword", "manfaz.keyAlias", "manfaz.keyPassword"
).all(signingProperties::containsKey)

android {
    namespace = "com.manfaz.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manfaz.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.4.1"
        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("fa", "en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("manfaz.storeFile"))
                storePassword = signingProperties.getProperty("manfaz.storePassword")
                keyAlias = signingProperties.getProperty("manfaz.keyAlias")
                keyPassword = signingProperties.getProperty("manfaz.keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
    // Per-ABI APKs avoid shipping native cores for four CPU architectures to every phone.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Config import: QR scanning
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.core)
    implementation(libs.accompanist.permissions)
    implementation(libs.snakeyaml)

    // Xray core (AndroidLibXrayLite) — real proxy engine
    implementation(":libv2ray@aar")

    debugImplementation(libs.androidx.ui.tooling)
}
