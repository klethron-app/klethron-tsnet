import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
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
        fun go(directory: File, vararg args: String, env: Map<String, String> = emptyMap(), tool: String = "go"): String {
            val output = ByteArrayOutputStream()
            val result = exec.exec {
                workingDir(directory)
                commandLine(tool, *args)
                // Override ambient settings that would change the shipped code without changing inputs.
                // Go reads empty variables from the user's go env file, so ignore that file.
                environment(mapOf("GOENV" to "off", "GOTOOLCHAIN" to "local", "GOWORK" to "off",
                    "GOFLAGS" to "", "GOEXPERIMENT" to "", "GOFIPS140" to "off", "GOAMD64" to "v1", "GOARM64" to "v8.0",
                    "CGO_CFLAGS" to "-O2 -g", "CGO_CPPFLAGS" to "", "CGO_LDFLAGS" to "-O2 -g") + env)
                standardOutput = output
                isIgnoreExitValue = true
            }
            // staticcheck reports on standard output.
            if (result.exitValue != 0) throw GradleException("${File(tool).name} ${args.first()} failed:\n$output")
            return output.toString(Charsets.UTF_8)
        }
        val source = sources.get().asFile
        if (!go(source, "version").startsWith("go version go${goVersion.get()} ")) {
            throw GradleException("Install Go ${goVersion.get()}, then rerun ./gradlew build")
        }
        // The NDK ships Linux binaries for x86_64 only, and universal macOS binaries as darwin-x86_64.
        val host = when (System.getProperty("os.name")) {
            "Linux" -> if (System.getProperty("os.arch") == "amd64") "linux-x86_64" else null
            "Mac OS X" -> "darwin-x86_64"
            else -> null
        } ?: throw GradleException("Build native code on Linux x86_64 or macOS with ./gradlew build")
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
        // Patch an isolated copy of the pinned source, without changing the module cache.
        // See the distributed TAILSCALE-MODIFICATIONS notice for the upstream diff.
        val module = JsonSlurper().parseText(go(source, "mod", "download", "-json", "tailscale.com")) as Map<*, *>
        go(source, "mod", "verify")
        val upstream = File(module["Dir"] as String)
        val workspace = temporaryDir.resolve("go")
        files.sync {
            into(workspace)
            from(source)
            from(upstream) {
                into("tailscale")
                // Module-cache directories are read-only; our private build copy must be writable.
                dirPermissions { unix("0755") }
                filePermissions { unix("0644") }
            }
        }
        // Replaces the only occurrence of `old` in an upstream file with the given SHA-256.
        fun patch(path: String, sha256: String, old: String, new: String) {
            val original = upstream.resolve(path).readBytes()
            val text = original.toString(Charsets.UTF_8)
            if (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)) != sha256 ||
                text.split(old).size != 2) {
                throw GradleException("Pinned $path changed; review TAILSCALE-MODIFICATIONS before building")
            }
            workspace.resolve("tailscale/$path").writeText(text.replace(old, new))
        }
        // tailscale/tailscale#22248: a failed logout must not forget the node key.
        val forgetKey = "\tc.mu.Lock()\n\tc.persist = new(persist.Persist).View()\n\tc.mu.Unlock()\n"
        patch("control/controlclient/direct.go", "a28e97e2a6822604182cdcfdd319ea9bb10c29b17e8b4592289c1cf2278160a9",
            forgetKey, "\tif err == nil {\n" + forgetKey.replace("\t", "\t\t") + "\t}\n")
        // Names that the tailnet's DNS does not answer must not reach the device's resolver.
        patch("tsnet/dns_lookup.go", "b49ecd66f1b9f4de7e7ef20061148a0e903de2e847627db38c88715c6964dc93",
            "return c.hostLookup(ctx, network, hostFB)", "return candidateResult{servfail: true, err: firstErr}")
        workspace.resolve("go.mod").appendText("\nreplace tailscale.com => ./tailscale\n")
        val flags = arrayOf("-mod=readonly", "-trimpath", "-buildvcs=false", "-tags=" + buildTags.get().joinToString(","))
        val target = mapOf("GOOS" to "android", "GOARCH" to arch, "CGO_ENABLED" to "1", "CC" to compiler.absolutePath)
        // Check the exact configuration we ship; matching flags share the build cache with go build.
        go(workspace, "vet", *flags, ".", env = target)
        // staticcheck is pinned by go.mod's tool directive and built for the host.
        go(workspace, ".", env = target + ("GOFLAGS" to flags.joinToString(" ")),
            tool = go(workspace, "tool", "-n", "staticcheck").trim())
        go(workspace, "build", *flags, "-buildmode=c-shared",
            "-ldflags=-s -buildid=", // The NDK aligns to 16 KiB; VerifyNativeAar checks it.
            "-o", outputDirectory.file("${abi.get()}/libklethron_tsnet.so").get().asFile.absolutePath, ".", env = target)
    }
}
