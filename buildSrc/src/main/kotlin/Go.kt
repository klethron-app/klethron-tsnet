import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations

fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun ExecOperations.checkGoVersion(directory: File, version: String) {
    if (!go(directory, "version").startsWith("go version go$version ")) {
        throw GradleException("Install Go $version, then rerun ./gradlew build")
    }
}

fun ExecOperations.go(directory: File, vararg args: String, env: Map<String, String> = emptyMap(), tool: String = "go"): String {
    val output = ByteArrayOutputStream()
    val result = exec {
        workingDir(directory)
        commandLine(tool, *args)
        // Override ambient settings that would change the shipped code without changing inputs.
        // Go reads empty variables from the user's go env file, so ignore that file.
        environment(mapOf("GOENV" to "off", "GOTOOLCHAIN" to "local", "GOWORK" to "off",
            "GOOS" to "", "GOARCH" to "", "CGO_ENABLED" to "",
            "GOFLAGS" to "", "GOEXPERIMENT" to "", "GOFIPS140" to "off", "GOAMD64" to "v1", "GOARM64" to "v8.0",
            "CGO_CFLAGS" to "-O2 -g", "CGO_CPPFLAGS" to "", "CGO_LDFLAGS" to "-O2 -g") + env)
        standardOutput = output
        isIgnoreExitValue = true
    }
    // staticcheck reports on standard output.
    if (result.exitValue != 0) throw GradleException("${File(tool).name} ${args.first()} failed:\n$output")
    return output.toString(Charsets.UTF_8)
}
