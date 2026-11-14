import java.io.DataInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
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
                requireNative(!Regex("klethron_probe|Probe_nativeCall|klethron_timing|klethrontiming|Timing_initialize|timing_debug.go|timing.c")
                    .containsMatchIn(String(bytes, Charsets.ISO_8859_1)), "${entry.name} includes debug instrumentation")
                sizes.append("${entry.name}\t${entry.size}\t${entry.compressedSize}\n")
            }
            ZipInputStream(zip.getInputStream(zip.getEntry("classes.jar"))).use { classes ->
                while (true) {
                    val entry = classes.nextEntry ?: break
                    val bytes = classes.readBytes()
                    val text = bytes.toString(Charsets.ISO_8859_1)
                    requireNative(!Regex("ConnectTiming|elapsedRealtimeNanos|android/util/Log|nativeEvent|native_load|control_keys")
                        .containsMatchIn(text), "${entry.name} includes debug logging")
                    if (entry.name.endsWith(".class") && entry.name != "com/klethron/tsnet/Timing.class") {
                        requireNative(!referencesTiming(bytes), "${entry.name} calls debug instrumentation")
                    }
                }
            }
        }
        sizes.append("AAR\t${file.length()}\t${file.length()}\n")
        report.get().asFile.writeText(sizes.toString())
        logger.lifecycle(sizes.toString())
    }

    // javac retains an unused Class constant for a folded ENABLED reference. Reject executable
    // dependencies: method/field constants, including references used by invokedynamic handles.
    private fun referencesTiming(bytes: ByteArray): Boolean {
        DataInputStream(bytes.inputStream()).use { input ->
            if (input.readInt() != 0xcafebabe.toInt()) return false
            input.readInt() // Minor/major version.
            val count = input.readUnsignedShort()
            val names = arrayOfNulls<String>(count)
            val classes = IntArray(count)
            val owners = mutableListOf<Int>()
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> names[index] = input.readUTF()
                    7 -> classes[index] = input.readUnsignedShort()
                    9, 10, 11 -> { owners += input.readUnsignedShort(); input.readUnsignedShort() }
                    3, 4, 12, 17, 18 -> input.skipNBytes(4)
                    5, 6 -> { input.skipNBytes(8); index++ }
                    8, 16, 19, 20 -> input.skipNBytes(2)
                    15 -> input.skipNBytes(3)
                    else -> throw GradleException("Unknown class constant $tag; update VerifyNativeAar and rerun ./gradlew :tsnet:verifyNativeAar")
                }
                index++
            }
            return owners.any { names[classes[it]] == "com/klethron/tsnet/Timing" }
        }
    }
}
