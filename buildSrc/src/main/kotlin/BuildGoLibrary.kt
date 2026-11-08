import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import groovy.json.JsonSlurper
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

// Incremental locally; do not share cached binaries before hermetic toolchain provisioning exists.
@DisableCachingByDefault(because = "Uses locally installed, version-checked Go and NDK toolchains")
abstract class BuildGoLibrary @Inject constructor(
    private val exec: ExecOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    @get:Internal abstract val sdkDirectory: DirectoryProperty
    @get:Input abstract val ndkVersion: Property<String>
    @get:Input abstract val goVersion: Property<String>
    @get:Input abstract val api: Property<Int>
    @get:Input abstract val abi: Property<String>
    @get:Input abstract val buildTags: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init {
        buildTags.convention(emptyList())
    }

    @TaskAction
    fun build() {
        val version = ByteArrayOutputStream()
        exec.exec {
            commandLine("go", "version")
            environment("GOTOOLCHAIN", "local")
            standardOutput = version
        }
        if (!version.toString().startsWith("go version go${goVersion.get()} ")) {
            throw GradleException("Install Go ${goVersion.get()}, then rerun ./gradlew :tsnet:assembleRelease")
        }
        val host = when (System.getProperty("os.name")) {
            "Linux" -> "linux-x86_64"
            "Mac OS X" -> "darwin-x86_64"
            else -> throw GradleException("Build native code on Linux or macOS with ./gradlew build")
        }
        val ndk = sdkDirectory.dir("ndk/${ndkVersion.get()}").get().asFile
        val (arch, triple) = when (abi.get()) {
            "arm64-v8a" -> "arm64" to "aarch64-linux-android"
            "x86_64" -> "amd64" to "x86_64-linux-android"
            else -> throw GradleException("Unsupported ABI ${abi.get()}; use arm64-v8a or x86_64")
        }
        val compiler = ndk.resolve("toolchains/llvm/prebuilt/$host/bin/$triple${api.get()}-clang")
        if (!compiler.isFile) {
            throw GradleException("Install the NDK with sdkmanager 'ndk;${ndkVersion.get()}', then rerun ./gradlew build")
        }
        val output = outputDirectory.get().asFile.resolve(abi.get())
        output.mkdirs()
        // Patch an isolated copy of the pinned source, without changing the module cache.
        // See the distributed TAILSCALE-MODIFICATIONS notice for the upstream diff.
        val moduleInfo = ByteArrayOutputStream()
        exec.exec {
            workingDir(sources)
            commandLine("go", "mod", "download", "-json", "tailscale.com")
            environment("GOTOOLCHAIN", "local")
            environment("GOWORK", "off")
            standardOutput = moduleInfo
        }
        val module = JsonSlurper().parseText(moduleInfo.toString(Charsets.UTF_8)) as Map<*, *>
        exec.exec {
            workingDir(sources)
            commandLine("go", "mod", "verify")
            environment("GOTOOLCHAIN", "local")
            environment("GOWORK", "off")
        }
        val upstream = File(module["Dir"] as String)
        val workspace = temporaryDir.resolve("go")
        files.sync { from(sources); into(workspace) }
        files.sync {
            from(upstream)
            into(workspace.resolve("tailscale"))
            // Module-cache directories are read-only; our private build copy must be writable.
            dirPermissions { unix("0755") }
            filePermissions { unix("0644") }
        }
        fun patch(path: String, expectedHash: String, before: String, after: String) {
            val original = upstream.resolve(path).readBytes()
            val checksum = MessageDigest.getInstance("SHA-256").digest(original)
                .joinToString("") { "%02x".format(it) }
            val source = original.toString(Charsets.UTF_8)
            if (checksum != expectedHash || source.split(before).size != 2) {
                throw GradleException("Pinned $path changed; review the no-logtail patches before building")
            }
            workspace.resolve("tailscale/$path").writeText(source.replace(before, after))
        }
        patch("tsnet/tsnet.go", "cfb60541a65b6e1b7c16a4a6ea0666dab41572eaa80fd3733addcf78d3f8213b",
            "if testenv.InTest() {", "if !buildfeatures.HasLogTail || testenv.InTest() {")
        val sockstatInit = "\tb.sockstatLogger, err = sockstatlog.NewLogger(logpolicy.LogsDir(logf), logf, logID, netMon, sys.HealthTracker.Get(), sys.Bus.Get())\n" +
            "\tif err != nil {\n\t\tlogf(\"error setting up sockstat logger: %v\", err)\n\t}\n"
        patch("ipn/ipnlocal/local.go", "e14a75f9cf70209a27178bedb7fd013278d642319bd2e760bb86c75a26232537",
            sockstatInit, "\tif buildfeatures.HasLogTail {\n" +
                sockstatInit.lines().dropLast(1).joinToString("") { "\t$it\n" } + "\t}\n")
        workspace.resolve("go.mod").appendText("\nreplace tailscale.com => ./tailscale\n")
        exec.exec {
            workingDir(workspace)
            environment("GOOS", "android")
            environment("GOARCH", arch)
            environment("CGO_ENABLED", "1")
            environment("CC", compiler.absolutePath)
            environment("GOTOOLCHAIN", "local")
            environment("GOWORK", "off")
            commandLine(
                "go", "build", "-mod=readonly", "-trimpath", "-buildvcs=false",
                "-tags=" + buildTags.get().joinToString(","),
                "-buildmode=c-shared",
                "-ldflags=-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384",
                "-o", output.resolve("libklethron_tsnet.so").absolutePath, "."
            )
        }
    }
}
