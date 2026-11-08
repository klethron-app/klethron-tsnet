import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
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

    @get:Input abstract val goVersion: Property<String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        fun requireNotice(condition: Boolean, message: String) {
            if (!condition) throw GradleException("$message. Review THIRD-PARTY-NOTICES.md and the checked-in notices")
        }
        val directory = notices.get().asFile
        val inventory = directory.resolve("GO-MODULES.tsv").readLines()
        requireNotice(inventory.firstOrNull() == "module\tversion\tsum\tnotices", "Malformed Go module inventory header")
        for (path in listOf("GO-LICENSE", "GO-PATENTS", "JSPECIFY-LICENSE")) {
            requireNotice(directory.resolve(path).isFile && directory.resolve(path).length() > 0, "Missing notice $path")
        }
        val modules = inventory.drop(1).map { line ->
            val fields = line.split('\t')
            requireNotice(fields.size == 4 && fields.all { it.isNotBlank() }, "Malformed Go module inventory row")
            fields
        }
        requireNotice(modules.isNotEmpty() && modules.map { it[0] }.distinct().size == modules.size,
            "Go module inventory must be nonempty with unique module paths")
        val expected = modules.map { it.take(3) }.toSet()
        for (module in modules) {
            for (path in module[3].split(',')) {
                requireNotice(!path.startsWith('/') && path.split('/').none { it == ".." }, "Invalid notice path $path")
                requireNotice(directory.resolve(path).isFile && directory.resolve(path).length() > 0,
                    "Missing notice $path for ${module[0]}")
            }
        }
        val results = StringBuilder("entry\tgo_version\tmodule_count\n")
        ZipFile(aar.get().asFile).use { zip ->
            // Compare all checked-in notices, including Go, JSpecify, and the inventory itself.
            for (file in directory.walkTopDown().filter { it.isFile }) {
                val path = "assets/klethron-tsnet/licenses/" + file.relativeTo(directory).invariantSeparatorsPath
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
                    environment("GOWORK", "off")
                    standardOutput = output
                }
                val lines = output.toString(Charsets.UTF_8).lineSequence().toList()
                requireNotice(lines.first().endsWith(": go${goVersion.get()}"), "Unexpected Go version in ${entry.name}")
                val fields = lines.drop(1).map { it.trim().split(Regex("\\s+")) }
                // The build verifies upstream checksums before applying our documented tsnet patch.
                // Local replacements have no binary module checksum; accept only this exact one.
                val replacements = fields.withIndex().filter { it.value.first() == "=>" }
                requireNotice(replacements.size == 1 && replacements.all { (index, row) ->
                    row == listOf("=>", "./tailscale", "(devel)") && index > 0 &&
                        fields[index - 1].take(2) == listOf("dep", "tailscale.com")
                }, "Unexpected Go module replacement; only the documented tsnet patch is allowed")
                requireNotice(directory.resolve("TAILSCALE-MODIFICATIONS").isFile, "Missing Tailscale patch notice")
                val upstreamTailscale = expected.single { it[0] == "tailscale.com" }
                val actual = fields.filter { it.first() == "dep" }.map {
                    val dependency = it.drop(1)
                    if (dependency.first() == "tailscale.com" && dependency.size == 2) {
                        dependency + upstreamTailscale[2]
                    } else dependency
                }.toSet()
                requireNotice(actual == expected,
                    "Go module inventory differs in ${entry.name}: missing=${actual - expected}, stale=${expected - actual}")
                results.append("${entry.name}\t${goVersion.get()}\t${actual.size}\n")
            }
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(results.toString())
        }
        logger.lifecycle(results.toString())
    }
}
