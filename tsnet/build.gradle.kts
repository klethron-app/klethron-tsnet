import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "com.klethron"

android {
    lint.warningsAsErrors = true
    lint.informational += listOf("AndroidGradlePluginVersion", "GradleDependency")
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    // Go already strips these libraries; do not run the Android strip task again.
    packaging.jniLibs.keepDebugSymbols += "**/libklethron_tsnet.so"
}

// Upstream features this client never uses. gradle/native-feature-audit.init.gradle measures one's size.
val omittedFeatures = listOf(
    // Out of scope: embedded SSH server, web UI, Taildrive, app connectors, self-update.
    "ssh", "webclient", "drive", "appconnectors", "clientupdate",
    // The node only connects out: no PeerAPI server, Serve/Funnel, advertised routes or exit node, nor exit node use.
    "peerapiserver", "serve", "advertiseroutes", "advertiseexitnode", "useexitnode", "exitnodehealth",
    // No peer relay server, port listing or admin-driven remote configuration.
    "relayserver", "portlist", "remoteconfig",
    // No log upload/buffering, debug hooks (including SSLKEYLOGFILE), packet capture, flow logs,
    // rejection diagnostics, metrics or on-disk netmap and DNS caches.
    "logtail", "debug", "capture", "netlog", "connreject", "usermetrics", "clientmetrics", "cachenetmap",
    "dnsresolvecache",
    // Ignore host-process environment and policy: proxy variables, OAuth secrets, MDM settings.
    "useproxy", "oauthkey", "syspolicy",
    // Not applicable: Linux GRO, TPMs, cloud metadata, captive-portal probes, Unix-socket LocalAPI users.
    "gro", "tpm", "cloud", "captiveportal", "unixsocketidentity",
    // No TLS certificates for this node's own listeners.
    "acme",
    // TLS trusts only Android's system CA store, not Tailscale's baked-in Let's Encrypt roots.
    "bakedroots",
).map { "ts_omit_$it" }

// AGP's Dokka-based Javadoc drops the code spans and links of Markdown doc comments; the JDK renders them.
val javadoc = tasks.register<Javadoc>("releaseJavadoc") {
    source("src/main/java")
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("-no-fonts", true)
        addBooleanOption("Xdoclint:all,-missing", true) // Parameters are described in the prose.
        addBooleanOption("Werror", true)
    }
}

androidComponents.onVariants { variant ->
    val buildType = variant.buildType!!
    val debug = buildType == "debug"
    for (abi in listOf("arm64-v8a", "x86_64")) {
        val buildGo = tasks.register<BuildGoLibrary>(
            "buildGo" + (if (abi == "arm64-v8a") "Arm64" else "X86_64") + (if (debug) "Debug" else "")
        ) {
            sources.set(layout.projectDirectory.dir("src/main/go"))
            sdkDirectory.set(androidComponents.sdkComponents.sdkDirectory)
            ndkVersion.set(libs.versions.ndk)
            goVersion.set(libs.versions.go)
            api.set(libs.versions.minSdk.get().toInt())
            this.abi.set(abi)
            buildTags.set(omittedFeatures + if (debug) listOf("klethron_test") else emptyList())
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildGo, BuildGoLibrary::outputDirectory)
    }

    // Audit the binaries actually packaged in each variant, not the wider go.mod graph.
    val aar = variant.artifacts.get(SingleArtifact.AAR)
    tasks.register<VerifyNativeNotices>("verify${variant.name.replaceFirstChar { it.uppercase() }}NativeNotices") {
        this.aar.set(aar)
        notices.set(layout.projectDirectory.dir("src/main/assets/klethron-tsnet/licenses"))
        if (debug) debugNotices.set(layout.projectDirectory.dir("src/debug/assets/klethron-tsnet/licenses"))
        goSum.set(layout.projectDirectory.file("src/main/go/go.sum"))
        goVersion.set(libs.versions.go)
        ndkVersion.set(libs.versions.ndk)
        releaseTags.set(omittedFeatures)
        report.set(layout.buildDirectory.file("reports/native/notices-$buildType.tsv"))
    }
    if (!debug) {
        tasks.register<VerifyNativeAar>("verifyNativeAar") {
            this.aar.set(aar)
            report.set(layout.buildDirectory.file("reports/native/sizes.tsv"))
        }
        javadoc.configure { classpath = variant.compileClasspath + files(androidComponents.sdkComponents.bootClasspath) }
    }
}
tasks.named("check") { dependsOn(javadoc, tasks.withType<VerifyNativeNotices>(), tasks.withType<VerifyNativeAar>()) }
val javadocJar = tasks.register<Jar>("releaseJavadocJar") {
    archiveClassifier = "javadoc"
    from(javadoc)
}

dependencies {
    // Consumers compile against our nullness annotations; their APKs need no annotation classes.
    compileOnlyApi(libs.jspecify)
    // Lint, Error Prone and Kotlin's return-value checker match these by name in our class files, so
    // consumers need neither artifact (androidx.annotation would also export the Kotlin stdlib).
    compileOnly(libs.androidx.annotation)
    compileOnly(libs.errorprone.annotations)
    androidTestCompileOnly(libs.errorprone.annotations)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifact(javadocJar)
            pom {
                name = "Klethron tsnet"
                description = "Android JNI bindings for an embedded Tailscale tsnet node"
                url = "https://github.com/klethron-app/klethron-tsnet"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        comments = "This project's code. The native libraries bundle Tailscale, Go and other code " +
                            "under their own licences, shipped in assets/klethron-tsnet/licenses/ (THIRD-PARTY-NOTICES.md)."
                    }
                }
                developers {
                    developer {
                        name = "Fredrik Fornwall"
                        organization = "Klethron"
                        organizationUrl = "https://github.com/klethron-app"
                    }
                }
                scm {
                    url = "https://github.com/klethron-app/klethron-tsnet"
                    connection = "scm:git:https://github.com/klethron-app/klethron-tsnet.git"
                    developerConnection = "scm:git:ssh://git@github.com/klethron-app/klethron-tsnet.git"
                    // The tag pins the source, and with it the Tailscale commit, Go and NDK of a release.
                    if (!version.toString().endsWith("-SNAPSHOT")) tag = "v$version"
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
