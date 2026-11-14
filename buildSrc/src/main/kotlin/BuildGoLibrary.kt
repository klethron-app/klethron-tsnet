import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
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
    @get:Input abstract val buildTags: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val workspace = sources.get().asFile
        exec.checkGoVersion(workspace, goVersion.get())
        // The NDK ships Linux binaries for x86_64 only, and universal macOS binaries as darwin-x86_64.
        val host = if (System.getProperty("os.name") == "Mac OS X") "darwin-x86_64" else "linux-x86_64"
        val ndk = sdkDirectory.dir("ndk/${ndkVersion.get()}").get().asFile
        val (arch, triple) = when (abi.get()) {
            "arm64-v8a" -> "arm64" to "aarch64-linux-android"
            "x86_64" -> "amd64" to "x86_64-linux-android"
            else -> throw GradleException("Unsupported ABI ${abi.get()}; use arm64-v8a or x86_64")
        }
        val compiler = ndk.resolve("toolchains/llvm/prebuilt/$host/bin/$triple${api.get()}-clang")
        if (!compiler.isFile) {
            throw GradleException("On Linux x86_64 or macOS, install the NDK with sdkmanager 'ndk;${ndkVersion.get()}', then rerun ./gradlew build")
        }
        val flags = arrayOf("-mod=readonly", "-trimpath", "-buildvcs=false", "-tags=" + buildTags.get().joinToString(","))
        val target = mapOf("GOOS" to "android", "GOARCH" to arch, "CGO_ENABLED" to "1", "CC" to compiler.absolutePath)
        // Check the exact configuration we ship; matching flags share the build cache with go build.
        exec.go(workspace, "vet", *flags, ".", env = target)
        // staticcheck is pinned by go.mod's tool directive and built for the host.
        exec.go(workspace, ".", env = target + ("GOFLAGS" to flags.joinToString(" ")),
            tool = exec.go(workspace, "tool", "-n", "staticcheck", env = mapOf("GOFLAGS" to "-mod=readonly")).trim())
        exec.go(workspace, "build", *flags, "-buildmode=c-shared",
            "-ldflags=-s -buildid=", // The NDK aligns to 16 KiB.
            "-o", outputDirectory.file("${abi.get()}/libklethron_tsnet.so").get().asFile.absolutePath, ".", env = target)
    }
}
