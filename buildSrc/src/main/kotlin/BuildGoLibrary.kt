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

fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

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
        val source = sources.get().asFile
        if (!go(source, "version").startsWith("go version go${goVersion.get()} ")) {
            throw GradleException("Install Go ${goVersion.get()}, then rerun ./gradlew build")
        }
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
        fun patch(path: String, hash: String, vararg changes: Pair<String, String>) {
            val original = upstream.resolve(path).readBytes()
            if (sha256(original) != hash) throw GradleException("Pinned $path changed; review TAILSCALE-MODIFICATIONS")
            var text = original.toString(Charsets.UTF_8)
            for ((old, new) in changes) {
                if (text.split(old).size != 2) throw GradleException("Patch does not match $path")
                text = text.replace(old, new)
            }
            workspace.resolve("tailscale/$path").writeText(text)
        }
        // tailscale/tailscale#22248: a failed logout must not forget the node key.
        val forgetKey = "\tc.mu.Lock()\n\tc.persist = new(persist.Persist).View()\n\tc.mu.Unlock()\n"
        patch("control/controlclient/direct.go", "ccdcc186decb2efe76c98af7e4914b77f15a7d7eda9406b1bb2b441c7fa1575a",
            forgetKey to "\tif err == nil {\n" + forgetKey.replace("\t", "\t\t") + "\t}\n")
        // Names that the tailnet's DNS does not answer must not reach the device's resolver.
        patch("tsnet/dns_lookup.go", "b49ecd66f1b9f4de7e7ef20061148a0e903de2e847627db38c88715c6964dc93",
            "return c.hostLookup(ctx, network, hostFB)" to "return candidateResult{servfail: true, err: firstErr}")
        // DNS TCP retries and DoH must use netstack for tailnet resolvers, including on Android.
        patch("net/dns/resolver/forwarder.go", "804a86c6264a7fcb3d2795e33d047a88502836ca74f5eaa516331acc8e0e4b94",
            "\t\"tailscale.com/net/tsdial\"" to "\t\"tailscale.com/net/tlsdial\"\n\t\"tailscale.com/net/tsdial\"",
            "\t\tMinVersion: tls.VersionTLS13," to "\t\tMinVersion: tls.VersionTLS13,\n\t\tRootCAs: tlsdial.SystemRoots,",
            "func (f *forwarder) getDialerType() netx.DialFunc {\n" +
                "\tif ShouldUseRoutes(f.controlKnobs) {\n\t\treturn f.dialer.UserDial\n\t}\n\treturn f.dialer.SystemDial\n}" to """
                func (f *forwarder) getDialerType() netx.DialFunc {
                    dial := f.dialer.SystemDial
                    if ShouldUseRoutes(f.controlKnobs) {
                        dial = f.dialer.UserDial
                    }
                    return func(ctx context.Context, network, address string) (net.Conn, error) {
                        if ipp, err := netip.ParseAddrPort(address); err == nil {
                            ipp = netip.AddrPortFrom(ipp.Addr().Unmap(), ipp.Port())
                            if f.dialer.UseNetstackForIP != nil && f.dialer.UseNetstackForIP(ipp.Addr()) {
                                if f.dialer.NetstackDialTCP == nil {
                                    return nil, errors.New("dialer not initialized correctly: no NetstackDialTCP")
                                }
                                return f.dialer.NetstackDialTCP(ctx, ipp)
                            }
                        }
                        return dial(ctx, network, address)
                    }
                }
            """.trimIndent())
        // Control can supply mixed-case split-DNS routes; wire query names are lowercased.
        patch("util/dnsname/dnsname.go", "5608189341565036e81b5d09c808034dd2f44e9d9cd8c7c9b7cd90764a7cdf3c",
            "\tif f == other {" to "\tif strings.EqualFold(string(f), string(other)) {",
            "\treturn strings.HasSuffix(other.WithTrailingDot(), cmp)" to
                "\tname := other.WithTrailingDot()\n\treturn len(name) >= len(cmp) && strings.EqualFold(name[len(name)-len(cmp):], cmp)")
        if ("klethron_test" in buildTags.get()) {
            patch("tstest/integration/testcontrol/testcontrol.go", "e8f952ff2ea480a7e52dc95faeb824f13b3e7102ef0b8afedd5f14077e890a53",
                "\t\tif isFollowup {" to "\t\tif isFollowup || !s.RequireAuth {")
        }
        val tsnetSource = upstream.resolve("tsnet/tsnet.go").readText()
        val hostnameSetup = "\texe, err := os.Executable()" + tsnetSource.substringAfter("\texe, err := os.Executable()")
            .substringBefore("\n\ts.rootPath =")
        val directorySetup = "\tif s.rootPath == \"\" {" + tsnetSource.substringAfter("\tif s.rootPath == \"\" {")
            .substringBefore("\n\ttsLogf :=")
        val fileStoreSetup = "\tif s.Store == nil {" + tsnetSource.substringAfter("\tif s.Store == nil {")
            .substringBefore("\n\tsys.Set(s.Store)")
        patch("tsnet/tsnet.go", "c37b19724de27737147f427e4a6e81e947736244848d55d6425a966cdbf24125",
            "\tgo s.printAuthURLLoop()\n" to "",
            hostnameSetup to "\tvar err error\n\ts.hostname = s.Hostname\n",
            "\ts.rootPath = s.Dir" to "\ts.rootPath = \"\"",
            directorySetup to "\tif s.Store == nil { return errors.New(\"StateStore is required\") }\n",
            fileStoreSetup to "",
            "\t\"tailscale.com/ipn/store\"\n" to "",
            "\t\"runtime\"\n" to "")
        workspace.resolve("tailscale/tsnet/tsnet.go").appendText(
            "\n// Status reads directly from the backend; call only between Start and Close.\n" +
            "func (s *Server) Status() *ipnstate.Status { return s.lb.Status() }\n" +
            "\n// Logout waits for backend completion even when ctx is cancelled.\n" +
            "func (s *Server) Logout(ctx context.Context) error { return s.lb.Logout(ctx, ipnauth.Self) }\n")
        val tailnetSource = upstream.resolve("ipn/ipnlocal/tailnet-lock.go").readText()
        val loadTailnet = "\troot := b.TailscaleVarRoot()" + tailnetSource.substringAfter("\troot := b.TailscaleVarRoot()")
            .substringBefore("\n\treturn nil\n}")
        val createTailnet = "\troot := b.TailscaleVarRoot()" + tailnetSource.substringAfterLast("\troot := b.TailscaleVarRoot()")
            .substringBefore("\n\tauthority, err := tka.Bootstrap")
        val tailnetDirectory = "// chonkPathLocked returns" + tailnetSource.substringAfter("// chonkPathLocked returns")
            .substringBefore("// tkaBootstrapFromGenesisLocked")
        val memoryWarning = "// noTailnetLockStateDirWarnable" + tailnetSource.substringAfter("// noTailnetLockStateDirWarnable")
            .substringBefore("// tkaFilterDeltaMutsLocked")
        patch("ipn/ipnlocal/tailnet-lock.go", "8d4e038c7e5d377fa3e734ccaa911d1715b7fadcfb8c0364365f4ccce67b2862",
            "\t\"path/filepath\"\n" to "",
            "\t\"tailscale.com/health\"\n" to "",
            memoryWarning to "",
            loadTailnet to """
                    authority, storage, err := b.openTailnetLock()
                    if err != nil { return err }
                    if authority != nil {
                        b.tka = &tkaState{profile: cp.ID(), authority: authority, storage: storage}
                    }
            """.trimIndent().prependIndent("\t"),
            createTailnet to "\t_, storage, err := b.openTailnetLock()\n\tif err != nil { return err }\n",
            tailnetDirectory to "")
        workspace.resolve("tailscale/ipn/ipnlocal/tailnet-lock.go").appendText("""

            // openTailnetLock uses caller-owned persistence. Plain memory stores are used by test fixtures.
            func (b *LocalBackend) openTailnetLock() (*tka.Authority, tka.CompactableChonk, error) {
                if store, ok := b.store.(interface {
                    OpenTailnetLock(ipn.ProfileID) (*tka.Authority, tka.CompactableChonk, error)
                }); ok { return store.OpenTailnetLock(b.pm.CurrentProfile().ID()) }
                return nil, tka.ChonkMem(), nil
            }

        """.trimIndent())
        patch("ipn/ipnlocal/profiles.go", "4e6a67d855b82e492123bc18f7b3cdc6075ceaf2176476057eff419281b6b585",
            "pm.store.WriteState(p.Key(), nil)" to "pm.deleteProfileState(p)",
            "pm.WriteState(profile.Key(), nil)" to "pm.deleteProfileState(profile)",
            "pm.WriteState(kp.Key(), nil)" to "pm.deleteProfileState(kp)")
        workspace.resolve("tailscale/ipn/ipnlocal/profiles.go").appendText("""

            // Delete authority history as well as credentials, including on logout and profile deduplication.
            func (pm *profileManager) deleteProfileState(profile ipn.LoginProfileView) error {
                // Keep pinned trust if deleting credentials fails or the process dies between writes.
                if err := pm.WriteState(profile.Key(), nil); err != nil { return err }
                if store, ok := pm.store.(interface { DeleteTailnetLock(ipn.ProfileID) error }); ok {
                    return store.DeleteTailnetLock(profile.ID())
                }
                return nil
            }

        """.trimIndent())
        patch("net/tlsdial/tlsdial.go", "49adc77dc68cf066fa695037cc41164509949dfa8a97e96a9c4fd334cd3b7e1e",
            "\t\t\tDNSName:       dialedHost," to "\t\t\tDNSName:       dialedHost,\n\t\t\tRoots: SystemRoots,")
        workspace.resolve("tailscale/net/tlsdial/tlsdial.go").appendText(
            "\n// SystemRoots is supplied by Android before TLS is used.\nvar SystemRoots *x509.CertPool\n")
        workspace.resolve("go.mod").appendText("\nreplace tailscale.com => ./tailscale\n")
        val flags = arrayOf("-mod=readonly", "-trimpath", "-buildvcs=false", "-tags=" + buildTags.get().joinToString(","))
        val target = mapOf("GOOS" to "android", "GOARCH" to arch, "CGO_ENABLED" to "1", "CC" to compiler.absolutePath)
        // Check the exact configuration we ship; matching flags share the build cache with go build.
        go(workspace, "vet", *flags, ".", env = target)
        // staticcheck is pinned by go.mod's tool directive and built for the host.
        go(workspace, ".", env = target + ("GOFLAGS" to flags.joinToString(" ")),
            tool = go(workspace, "tool", "-n", "staticcheck").trim())
        go(workspace, "build", *flags, "-buildmode=c-shared",
            "-ldflags=-s -buildid=", // The NDK aligns to 16 KiB.
            "-o", outputDirectory.file("${abi.get()}/libklethron_tsnet.so").get().asFile.absolutePath, ".", env = target)
    }
}
