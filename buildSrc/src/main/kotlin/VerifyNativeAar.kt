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
            val entries = zip.entries().asSequence().filter { it.name.endsWith(".so") }.toList()
            val machines = mapOf("arm64-v8a" to 183, "x86_64" to 62)
            requireNative(entries.map { it.name }.toSet() == machines.keys.map {
                "jni/$it/libklethron_tsnet.so"
            }.toSet(), "AAR must contain exactly the two supported native libraries")
            for (entry in entries) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val binaryText = String(bytes, Charsets.ISO_8859_1)
                // Every instrumentation bridge exports klethron_*_probe and binds a *Test.nativeProbe method.
                requireNative(!Regex("klethron_[a-z]+_probe|Test_nativeProbe").containsMatchIn(binaryText),
                    "${entry.name} includes a debug-only test bridge")
                val elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                requireNative(elf.getInt(0) == 0x464c457f && bytes[4] == 2.toByte() && bytes[5] == 1.toByte(),
                    "${entry.name} must be little-endian ELF64")
                requireNative(elf.getShort(18).toInt() == machines[entry.name.split('/')[1]],
                    "${entry.name} has the wrong architecture")
                val offset = elf.getLong(32).toInt()
                val stride = elf.getShort(54).toInt() and 0xffff
                val count = elf.getShort(56).toInt() and 0xffff
                var loads = 0
                repeat(count) { index ->
                    val header = offset + index * stride
                    if (elf.getInt(header) == 1) {
                        loads++
                        requireNative(elf.getLong(header + 48) >= 16384 &&
                            elf.getLong(header + 8) % 16384 == elf.getLong(header + 16) % 16384,
                            "${entry.name} has a LOAD segment incompatible with 16 KiB pages")
                    }
                }
                requireNative(loads > 0, "${entry.name} has no LOAD segments")
                sizes.append("${entry.name}\t${entry.size}\t${entry.compressedSize}\n")
            }
        }
        sizes.append("AAR\t${file.length()}\t${file.length()}\n")
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sizes.toString())
        }
        logger.lifecycle(sizes.toString())
    }
}
