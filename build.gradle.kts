plugins {
    base
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

// Subprojects' check and assemble already run with the root's; also build both test APKs.
tasks.named("check") {
    dependsOn(":tsnet:assembleDebugAndroidTest", ":demo:assembleReleaseAndroidTest")
}
