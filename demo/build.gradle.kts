plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.klethron.tsnet.demo"
    defaultConfig {
        applicationId = "com.klethron.tsnet.demo"
        targetSdk = libs.versions.targetSdk.get().toInt()
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Any rules file replaces AGP's defaults, whose JNI keep rule would hide consumer-rule gaps.
            proguardFiles("rules.pro")
            testProguardFiles("rules.pro")
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
