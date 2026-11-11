import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Uses the locally installed Go tool to inspect binary build metadata")
abstract class VerifyNativeNotices @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notices: DirectoryProperty

    // Modules linked only into debug libraries by instrumentation test servers, with their notices.
    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val debugNotices: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val goSum: RegularFileProperty

    @get:Input abstract val goVersion: Property<String>
    @get:Input abstract val ndkVersion: Property<String>
    @get:Input abstract val releaseTags: ListProperty<String> // They select the files FILE-NOTICES reviews.
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        fun requireNotice(condition: Boolean, message: String) {
            if (!condition) throw GradleException("$message. Review THIRD-PARTY-NOTICES.md and the checked-in notices")
        }
        fun requireFile(file: File, message: String) = requireNotice(file.isFile && file.length() > 0, message)
        val directory = notices.get().asFile
        for (path in listOf("GO-LICENSE", "GO-PATENTS", "FILE-NOTICES", "UNICODE-LICENSE")) {
            requireFile(directory.resolve(path), "Missing notice $path")
        }
        // Per-file notices come from a manual source review; any toolchain, module or build tag change invalidates it.
        val reviewedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            directory.resolve("GO-MODULES.tsv").readBytes() + releaseTags.get().sorted().joinToString(",").toByteArray()))
        val reviewed = "Reviewed for Go ${goVersion.get()}, NDK ${ndkVersion.get()}, and GO-MODULES.tsv with the release\n" +
            "build tags (SHA-256 $reviewedHash)."
        requireNotice(reviewed in directory.resolve("FILE-NOTICES").readText(),
            "FILE-NOTICES is not reviewed for this build; after a review, record \"$reviewed\"")
        fun inventory(directory: File, name: String): List<List<String>> {
            val lines = directory.resolve(name).readLines()
            requireNotice(lines.firstOrNull() == "module\tversion\tsum\tnotices", "Malformed inventory header in $name")
            val modules = lines.drop(1).map { line ->
                val fields = line.split('\t')
                requireNotice(fields.size == 4 && fields.all { it.isNotBlank() }, "Malformed inventory row in $name")
                fields
            }
            for (module in modules) {
                for (path in module[3].split(',')) {
                    requireNotice(!path.startsWith('/') && path.split('/').none { it == ".." }, "Invalid notice path $path")
                    requireFile(directory.resolve(path), "Missing notice $path for ${module[0]}")
                }
            }
            return modules
        }
        val debugDirectory = debugNotices.orNull?.asFile
        val directories = listOfNotNull(directory, debugDirectory)
        val modules = inventory(directory, "GO-MODULES.tsv") +
            (debugDirectory?.let { inventory(it, "GO-MODULES-DEBUG.tsv") } ?: emptyList())
        requireNotice(modules.map { it[0] }.distinct().size == modules.size, "Go module inventories must have unique module paths")
        val expected = modules.map { it.take(3) }.toSet()
        // The binary records no checksum for the patched copy, so check the inventory's against go.sum.
        val upstreamTailscale = expected.single { it[0] == "tailscale.com" }
        requireNotice(upstreamTailscale.joinToString(" ") in goSum.get().asFile.readLines(),
            "GO-MODULES.tsv's tailscale.com row differs from go.sum")
        val results = StringBuilder("entry\tmodule_count\n")
        ZipFile(aar.get().asFile).use { zip ->
            for ((root, file) in directories.flatMap { root -> root.walkTopDown().filter { it.isFile }.map { root to it } }) {
                val path = "assets/klethron-tsnet/licenses/" + file.relativeTo(root).invariantSeparatorsPath
                val entry = zip.getEntry(path)
                requireNotice(entry != null, "AAR is missing $path")
                val packaged = zip.getInputStream(entry).use { it.readBytes() }
                requireNotice(packaged.contentEquals(file.readBytes()), "AAR notice differs from source: $path")
            }
            val libraries = zip.entries().asSequence().filter { it.name.endsWith(".so") }.toList()
            requireNotice(libraries.map { it.name }.sorted() == listOf(
                "jni/arm64-v8a/libklethron_tsnet.so", "jni/x86_64/libklethron_tsnet.so"
            ), "AAR must contain exactly the two supported native libraries")
            for (entry in libraries) {
                val library = temporaryDir.resolve("libklethron_tsnet.so")
                zip.getInputStream(entry).use { input -> library.outputStream().use { input.copyTo(it) } }
                val output = ByteArrayOutputStream()
                exec.exec {
                    commandLine("go", "version", "-m", library.absolutePath)
                    environment("GOTOOLCHAIN", "local")
                    standardOutput = output
                }
                val fields = output.toString(Charsets.UTF_8).lines().drop(1).map { it.trim().split(Regex("\\s+")) }
                // The build verifies upstream checksums before applying our documented tsnet patches.
                // Local replacements have no binary module checksum; accept only this exact one.
                val replacements = fields.indices.filter { fields[it].first() == "=>" }.map { fields[it - 1].take(2) + fields[it] }
                requireNotice(replacements == listOf(listOf("dep", "tailscale.com", "=>", "./tailscale", "(devel)")),
                    "Unexpected Go module replacement; only the documented tsnet patches are allowed")
                val actual = fields.filter { it.first() == "dep" }.map {
                    val dependency = it.drop(1)
                    if (dependency.first() == "tailscale.com" && dependency.size == 2) {
                        dependency + upstreamTailscale[2]
                    } else dependency
                }.toSet()
                requireNotice(actual == expected,
                    "Go module inventory differs in ${entry.name}: missing=${actual - expected}, stale=${expected - actual}")
                results.append("${entry.name}\t${actual.size}\n")
            }
        }
        report.get().asFile.writeText(results.toString())
        logger.lifecycle(results.toString())
    }
}
