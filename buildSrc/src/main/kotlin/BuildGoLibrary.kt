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

    @TaskAction
    fun build() {
        fun go(directory: File, vararg args: String, env: Map<String, String> = emptyMap()): String {
            val output = ByteArrayOutputStream()
            exec.exec {
                workingDir(directory)
                commandLine("go", *args)
                environment(env + mapOf("GOTOOLCHAIN" to "local", "GOWORK" to "off"))
                standardOutput = output
            }
            return output.toString(Charsets.UTF_8)
        }
        val source = sources.get().asFile
        if (!go(source, "version").startsWith("go version go${goVersion.get()} ")) {
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
        val module = JsonSlurper().parseText(go(source, "mod", "download", "-json", "tailscale.com")) as Map<*, *>
        go(source, "mod", "verify")
        val upstream = File(module["Dir"] as String)
        val workspace = temporaryDir.resolve("go")
        files.sync { from(source); into(workspace) }
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
            val text = original.toString(Charsets.UTF_8)
            if (checksum != expectedHash || text.split(before).size != 2) {
                throw GradleException("Pinned $path changed; review TAILSCALE-MODIFICATIONS before building")
            }
            workspace.resolve("tailscale/$path").writeText(text.replace(before, after))
        }
        // A failed logout must not forget the node key: the map poll continues and would save the
        // empty key over the login, leaving the node registered but unable to log out again.
        val forgetKey = "\tc.mu.Lock()\n\tc.persist = new(persist.Persist).View()\n\tc.mu.Unlock()\n"
        patch("control/controlclient/direct.go", "a28e97e2a6822604182cdcfdd319ea9bb10c29b17e8b4592289c1cf2278160a9",
            forgetKey, "\tif err == nil {\n" + forgetKey.lines().joinToString("") { "\t$it\n" } + "\t}\n")
        workspace.resolve("go.mod").appendText("\nreplace tailscale.com => ./tailscale\n")
        go(workspace, "build", "-mod=readonly", "-trimpath", "-buildvcs=false",
            "-tags=" + buildTags.get().joinToString(","), "-buildmode=c-shared",
            "-ldflags=-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384",
            "-o", output.resolve("libklethron_tsnet.so").absolutePath, ".",
            env = mapOf("GOOS" to "android", "GOARCH" to arch, "CGO_ENABLED" to "1", "CC" to compiler.absolutePath))
    }
}
