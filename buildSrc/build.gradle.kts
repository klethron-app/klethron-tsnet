plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// Gradle's embedded Kotlin cannot target Java 27 yet. buildSrc has no Java sources.
java.targetCompatibility = JavaVersion.VERSION_26
kotlin.compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26
