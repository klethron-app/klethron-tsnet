plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "com.klethron"
version = rootProject.version

android {
    enableKotlin = false
    namespace = "com.klethron.tsnet"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    // Go already strips these libraries; do not run the Android strip task again.
    packaging.jniLibs.keepDebugSymbols += "**/libklethron_tsnet.so"
}

// Upstream features this client never uses. See PLAN.md for the measured savings and reasons.
val omittedFeatures = listOf(
    // Out of scope: embedded SSH server, web UI, Taildrive, app connectors, self-update.
    "ssh", "webclient", "drive", "appconnectors", "clientupdate",
    // The node only connects out: no PeerAPI server, advertised routes or exit node, nor exit node use.
    "peerapiserver", "advertiseroutes", "advertiseexitnode", "useexitnode", "exitnodehealth",
    // No log upload/buffering, debug hooks (including SSLKEYLOGFILE), metrics or on-disk netmap cache.
    "logtail", "debug", "usermetrics", "cachenetmap",
    // Ignore host-process environment and policy: proxy variables, OAuth secrets, MDM settings.
    "useproxy", "oauthkey", "syspolicy",
    // No TLS certificates for this node's own listeners.
    "acme",
    // TLS trusts only Android's system CA store, not Tailscale's baked-in Let's Encrypt roots.
    "bakedroots",
)

val nativeTasks = listOf("release", "debug").associateWith { buildType ->
    listOf("arm64-v8a", "x86_64").map { abi ->
        val suffix = if (buildType == "debug") "Debug" else ""
        tasks.register<BuildGoLibrary>("buildGo" + (if (abi == "arm64-v8a") "Arm64" else "X86_64") + suffix) {
            sources.set(layout.projectDirectory.dir("src/main/go"))
            sdkDirectory.set(androidComponents.sdkComponents.sdkDirectory)
            ndkVersion.set(libs.versions.ndk)
            goVersion.set(libs.versions.go)
            api.set(libs.versions.minSdk.get().toInt())
            this.abi.set(abi)
            buildTags.set(omittedFeatures.map { "ts_omit_$it" } +
                if (buildType == "debug") listOf("klethron_test") else emptyList())
            outputDirectory.set(layout.buildDirectory.dir("generated/jniLibs/$buildType/$abi"))
        }
    }
}
androidComponents.onVariants { variant ->
    nativeTasks.getValue(variant.buildType!!).forEach { task ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task, BuildGoLibrary::outputDirectory)
    }
}

val verifyNativeAar = tasks.register<VerifyNativeAar>("verifyNativeAar") {
    dependsOn("bundleReleaseAar")
    aar.set(layout.buildDirectory.file("outputs/aar/tsnet-release.aar"))
    report.set(layout.buildDirectory.file("reports/native/sizes.tsv"))
}
tasks.named("check") { dependsOn(verifyNativeAar) }

// Audit the binaries actually packaged in each variant, not the wider go.mod graph.
for (buildType in listOf("release", "debug")) {
    val verifyNotices = tasks.register<VerifyNativeNotices>("verify" + buildType.replaceFirstChar { it.uppercase() } + "NativeNotices") {
        dependsOn("bundle" + buildType.replaceFirstChar { it.uppercase() } + "Aar")
        aar.set(layout.buildDirectory.file("outputs/aar/tsnet-$buildType.aar"))
        notices.set(layout.projectDirectory.dir("src/main/assets/klethron-tsnet/licenses"))
        if (buildType == "debug") debugNotices.set(layout.projectDirectory.dir("src/debug/assets/klethron-tsnet/licenses"))
        goVersion.set(libs.versions.go)
        report.set(layout.buildDirectory.file("reports/native/notices-$buildType.tsv"))
    }
    tasks.named("check") { dependsOn(verifyNotices) }
}

dependencies {
    api(libs.jspecify)
    // Lint, Error Prone and Kotlin's return-value checker match these by name in our class files, so
    // consumers need neither artifact (androidx.annotation would also export the Kotlin stdlib).
    compileOnly(libs.androidx.annotation)
    compileOnly(libs.errorprone.annotations)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "tsnet"
            afterEvaluate { from(components["release"]) }
            pom {
                name = "Klethron tsnet"
                description = "Android JNI bindings for an embedded Tailscale tsnet node"
                url = "https://github.com/klethron-app/klethron-tsnet"
                scm { url = "https://github.com/klethron-app/klethron-tsnet" }
                licenses {
                    license {
                        name = "Apache License, Version 2.0 (wrapper); dependencies retain their licences"
                        url = "https://github.com/klethron-app/klethron-tsnet/blob/main/THIRD-PARTY-NOTICES.md"
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "localBuild"
            url = rootProject.layout.buildDirectory.dir("maven").get().asFile.toURI()
        }
    }
}
