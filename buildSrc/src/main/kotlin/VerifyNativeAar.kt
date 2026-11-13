import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            // VerifyNativeNotices checks that these are exactly the two supported libraries.
            val machines = mapOf("arm64-v8a" to 183, "x86_64" to 62)
            for (entry in zip.entries().asSequence().filter { it.name.endsWith(".so") }) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                // The instrumentation test bridge exports klethron_probe and binds Probe.nativeCall.
                requireNative(!Regex("klethron_probe|Probe_nativeCall").containsMatchIn(String(bytes, Charsets.ISO_8859_1)),
                    "${entry.name} includes a debug-only test bridge")
                requireNative(bytes.size >= 64, "${entry.name} has a truncated ELF header")
                val elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                requireNative(elf.getInt(0) == 0x464c457f && bytes[4] == 2.toByte() && bytes[5] == 1.toByte() &&
                    elf.getShort(16).toInt() == 3, "${entry.name} must be a little-endian ELF64 shared library")
                requireNative(elf.getShort(18).toInt() == machines[entry.name.split('/')[1]],
                    "${entry.name} has the wrong architecture")
                val offset = elf.getLong(32)
                val stride = elf.getShort(54).toInt() and 0xffff
                val count = elf.getShort(56).toInt() and 0xffff
                requireNative(stride == 56 && count > 0 && offset >= 64 && offset <= bytes.size - count * 56L,
                    "${entry.name} has an invalid program header table")
                val loads = (0 until count).map { offset.toInt() + it * stride }.filter { elf.getInt(it) == 1 }
                requireNative(loads.isNotEmpty(), "${entry.name} has no LOAD segments")
                for (header in loads) {
                    val alignment = elf.getLong(header + 48)
                    requireNative(alignment >= 16384 && (alignment and (alignment - 1)) == 0L &&
                        elf.getLong(header + 8) % 16384 == elf.getLong(header + 16) % 16384,
                        "${entry.name} has a LOAD segment incompatible with 16 KiB pages")
                }
                sizes.append("${entry.name}\t${entry.size}\t${entry.compressedSize}\n")
            }
        }
        sizes.append("AAR\t${file.length()}\t${file.length()}\n")
        report.get().asFile.writeText(sizes.toString())
        logger.lifecycle(sizes.toString())
    }
}
