plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.klethron.tsnet.demo"
    defaultConfig {
        applicationId = "com.klethron.tsnet.demo"
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // No default rules, whose JNI keep rule would hide gaps in the library's consumer rules.
            testProguardFiles("test-rules.pro")
            // This demo is a consumer test, not a distributable application.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    testBuildType = "release"
}

dependencies {
    implementation(project(":tsnet"))
    compileOnly(libs.androidx.annotation)
}
