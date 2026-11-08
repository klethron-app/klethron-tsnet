import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
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
abstract class BuildGoLibrary @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    @get:Internal abstract val sdkDirectory: DirectoryProperty
    @get:Input abstract val ndkVersion: Property<String>
    @get:Input abstract val goVersion: Property<String>
    @get:Input abstract val api: Property<Int>
    @get:Input abstract val abi: Property<String>
    @get:Input abstract val includeTestBridge: Property<Boolean>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init {
        includeTestBridge.convention(false)
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
        exec.exec {
            workingDir(sources)
            environment("GOOS", "android")
            environment("GOARCH", arch)
            environment("CGO_ENABLED", "1")
            environment("CC", compiler.absolutePath)
            environment("GOTOOLCHAIN", "local")
            environment("GOWORK", "off")
            commandLine(
                "go", "build", "-mod=readonly", "-trimpath", "-buildvcs=false",
                "-tags=" + if (includeTestBridge.get()) "klethron_test" else "",
                "-buildmode=c-shared",
                "-ldflags=-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384",
                "-o", output.resolve("libklethron_tsnet.so").absolutePath, "."
            )
        }
    }
}
