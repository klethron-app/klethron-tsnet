plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.klethron.tsnet.demo"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()
    packaging.jniLibs.keepDebugSymbols += "**/libklethron_tsnet.so"
    defaultConfig {
        applicationId = "com.klethron.tsnet.demo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":tsnet"))
}
