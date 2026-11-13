import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyNativeAar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: RegularFileProperty

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        fun requireNative(condition: Boolean, message: String) {
            if (!condition) throw GradleException("$message. Fix the native build and rerun ./gradlew :tsnet:verifyNativeAar")
        }
        val file = aar.get().asFile
        val sizes = StringBuilder("entry\tuncompressed_bytes\tcompressed_bytes\n")
        ZipFile(file).use { zip ->
            for (entry in zip.entries().asSequence().filter { it.name.endsWith(".so") }) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                // The instrumentation test bridge exports klethron_probe and binds Probe.nativeCall.
                requireNative(!Regex("klethron_probe|Probe_nativeCall").containsMatchIn(String(bytes, Charsets.ISO_8859_1)),
                    "${entry.name} includes a debug-only test bridge")
                sizes.append("${entry.name}\t${entry.size}\t${entry.compressedSize}\n")
            }
        }
        sizes.append("AAR\t${file.length()}\t${file.length()}\n")
        report.get().asFile.writeText(sizes.toString())
        logger.lifecycle(sizes.toString())
    }
}
