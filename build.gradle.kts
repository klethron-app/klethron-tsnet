import net.ltgt.gradle.errorprone.errorprone

plugins {
    base
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.errorprone) apply false
}

// Subprojects' check and assemble already run with the root's; also build both test APKs.
tasks.named("check") {
    dependsOn(":tsnet:assembleDebugAndroidTest", ":demo:assembleReleaseAndroidTest")
}

// javac, doclint and Error Prone warnings fail the build; NullAway checks @NullMarked packages.
subprojects {
    apply(plugin = "net.ltgt.errorprone")
    dependencies {
        "errorprone"(rootProject.libs.errorprone.core)
        "errorprone"(rootProject.libs.nullaway)
    }
    // AGP has no JVM source sets, so its processor paths need Error Prone explicitly.
    configurations.matching { it.name in setOf("annotationProcessor", "androidTestAnnotationProcessor") }.configureEach {
        extendsFrom(configurations["errorprone"])
    }
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
