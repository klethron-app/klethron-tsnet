plugins {
    base
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

tasks.named("check") {
    dependsOn(":tsnet:check", ":tsnet:assembleDebugAndroidTest", ":demo:check")
}

tasks.named("assemble") {
    dependsOn(":tsnet:assembleRelease", ":demo:assembleDebug")
}
