import com.android.build.api.dsl.CommonExtension
import net.ltgt.gradle.errorprone.errorprone

plugins {
    base
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.errorprone) apply false
}

subprojects {
    val libs = rootProject.libs // A subproject's own accessor works only once it is evaluated.
    for (plugin in listOf("com.android.application", "com.android.library")) pluginManager.withPlugin(plugin) {
        tasks.named("check") { dependsOn("assembleAndroidTest") } // Also build the test APKs.
        extensions.configure<CommonExtension> {
            enableKotlin = false
            compileSdk = libs.versions.compileSdk.get().toInt()
            compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()
            buildToolsVersion = libs.versions.buildTools.get()
            defaultConfig.minSdk = libs.versions.minSdk.get().toInt()
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            compileOptions.sourceCompatibility = JavaVersion.VERSION_17
            compileOptions.targetCompatibility = JavaVersion.VERSION_17
            lint.warningsAsErrors = true
            lint.informational += listOf("AndroidGradlePluginVersion", "GradleDependency")
            // Go already strips the library; do not run the Android strip task again.
            packaging.jniLibs.keepDebugSymbols += "**/libklethron_tsnet.so"
        }
        dependencies {
            "androidTestImplementation"(libs.android.test.runner)
            "androidTestImplementation"(libs.junit)
            // AGP has no JVM source sets, so its processor paths need Error Prone explicitly.
            for (processor in listOf("annotationProcessor", "androidTestAnnotationProcessor")) {
                processor(libs.errorprone.core)
                processor(libs.nullaway)
            }
        }
    }

    apply(plugin = "net.ltgt.errorprone")
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-Xdoclint:all/private,-missing"))
        // Tests close nodes and connections inside try-with-resources to check repeated close.
        if (name.contains("AndroidTest")) options.compilerArgs.add("-Xlint:-try")
        options.errorprone {
            enabled = true
            error("NullAway")
            listOf("OnlyNullMarked", "JSpecifyMode", "ExhaustiveOverride", "HandleWildcardGenerics", "CheckOptionalEmptiness")
                .forEach { option("NullAway:$it") }
        }
    }
}
