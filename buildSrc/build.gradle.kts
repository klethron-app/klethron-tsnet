plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

// This sets the oldest build JDK: 25 is the oldest LTS whose Javadoc renders Markdown doc comments.
// (Gradle's embedded Kotlin cannot target the pinned JDK 27 either.) buildSrc has no Java sources.
java.targetCompatibility = JavaVersion.VERSION_25
kotlin.compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
