# Third-party notices

Notices ship in the AAR under `assets/klethron-tsnet/licenses/`, from
`tsnet/src/main/assets/klethron-tsnet/licenses/` (both variants) and `tsnet/src/debug/…` (debug only):

- Tailscale (BSD-3-Clause) at the commit [UPSTREAM.md](UPSTREAM.md) names: licence, patent notice and
  `TAILSCALE-MODIFICATIONS`, which documents the local patch.
- Go's runtime licence and patent notice, also covering Tailscale's Go-derived `tempfork` sources.
- JSpecify 1.0.1 (Apache-2.0): a transitive Maven dependency, not bundled; its
  [licence](https://github.com/jspecify/jspecify/blob/v1.0.1/LICENSE) is included as `JSPECIFY-LICENSE`.
- [`GO-MODULES.tsv`](tsnet/src/main/assets/klethron-tsnet/licenses/GO-MODULES.tsv): the 24 modules that
  `go version -m` reports for both ABIs and variants, with version, checksum and notice paths.
  [`GO-MODULES-DEBUG.tsv`](tsnet/src/debug/assets/klethron-tsnet/licenses/GO-MODULES-DEBUG.tsv) adds the
  five modules of the debug-only test control and DERP/STUN servers.
- [`FILE-NOTICES`](tsnet/src/main/assets/klethron-tsnet/licenses/FILE-NOTICES): notices of individual
  files and linked C runtime objects that the module licences do not cover, and
  `UNICODE-LICENSE` for generated Unicode tables (see [Per-file review](#per-file-review)).

Notices were copied verbatim from the checksum-verified module cache: each module's root licence and
patent notice, plus the nested `internal/snapref` and `zstd/internal/xxhash` licences of the compression
module, which are in the Android import graph.

The patch is applied to an isolated copy through a temporary `tailscale.com => ./tailscale`
replacement. The inventory's Tailscale checksum identifies the **unmodified upstream module**.

`./gradlew build` runs `verifyReleaseNativeNotices` and `verifyDebugNativeNotices`. They inspect each AAR's
libraries and fail on missing or stale modules, version or checksum drift, any other replacement, a
different Go version, or a packaged notice that differs from its checked-in source. They also fail unless
`FILE-NOTICES` names the current Go and NDK versions and the SHA-256 of `GO-MODULES.tsv`, so any
toolchain or module change requires a new per-file review. Reports are in
`tsnet/build/reports/native/notices-{release,debug}.tsv`. Nothing is generated or downloaded.

## Per-file review

Reviewed 2026-11-10 for the release libraries: all 2,734 Go, assembly, C, header and embedded files that
`go list -deps` selects for either ABI (1,381 from the standard library), searched for copyright, licence,
SPDX, public-domain and "derived/adapted/copied from" text and for `Code generated` headers.

- **Per-file notices** beyond the module licences are in `FILE-NOTICES`: Sun (FreeBSD msun) and Cephes
  code in Go's `math`, Inferno's MIT `memmove` (x86_64), gVisor's BSD-licensed `pkg/sync`, half-rs (MIT)
  in `x448/float16`, and Yann Collet's FiniteStateEntropy (BSD-2-Clause) in klauspost's `fse`, `huff0`
  and `zstd`. No module ships the last two texts, so they were taken from the
  [half-rs](https://github.com/starkat99/half-rs/blob/main/LICENSE-MIT) and
  [FiniteStateEntropy](https://github.com/Cyan4973/FiniteStateEntropy/blob/dev/LICENSE) repositories.
  gVisor's files point to Google's [BSD licence](https://developers.google.com/open-source/licenses/bsd),
  whose terms match `GO-LICENSE` word for word.
- **Covered without a separate notice:** Go Authors code copied into other modules (`GO-LICENSE`; the
  same terms as `golang.org/x/*`), Tailscale code adapted from groupcache and gVisor (both licences
  ship), fiat-crypto code in Go's `crypto` and `filippo.io/edwards25519` (its BSD-1-Clause binds only
  source redistribution), the public-domain AES (`aes_generic.go`), SLEEF (`exp_amd64.s`) and Keccak
  reference code, and Go's adaptation of Pornin's ecGFp5 square root, for which Go carries no notice.
- **Generated data:** Go's `unicode`, `golang.org/x/text/unicode/{bidi,norm}` and
  `golang.org/x/net/idna` (also vendored in the standard library) hold tables generated from Unicode
  17.0.0, so `UNICODE-LICENSE` ships, copied from [unicode.org](https://www.unicode.org/license.txt)
  (SHA-256 `e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96`). Other generated files
  hold facts or interfaces, not creative content: IANA registry numbers, the RFC 7541 HPACK table, Linux
  UAPI constants from `cgo -godefs` (`syscall`, `x/sys`, `x/net`), RE2's Perl character classes and
  WHATWG entity names. Code generators (avo, addchain, stringer, Tailscale's) add no licence.
- **Embedded resources:** only Tailscale's `VERSION.txt`, `ALPINE.txt`, `go.toolchain.rev`,
  `go.toolchain.next.rev` and `net/dnsfallback/dns-fallback-servers.json`, under Tailscale's licence. No
  `.syso` objects.
- **C/NDK runtime:** linker traces (`-Wl,--trace`, `-Map`; the resulting libraries are byte-identical to
  the release build) show that both ABIs statically link only Go and cgo objects (`runtime/cgo` is Go's;
  `jni.c` is ours), plus NDK r30's `crtbegin_so.o` and `crtend_so.o`. No compiler-rt, libunwind or libc++
  member is linked. `libc`, `libdl` and `liblog` are dynamic and come with the device. `_FORTIFY_SOURCE`
  is off, so bionic headers contribute only declarations. The CRT objects are built from bionic's
  `libc/arch-common/bionic` sources, BSD-2-Clause apart from Apache-2.0 `pthread_atfork.h`. The NDK does
  not record its bionic revision, so those headers were taken from bionic `main`, whose definitions match
  the objects' symbols.
- **Debug-only code** is not distributed. Its five extra modules ship root notices only.

To repeat the review after a native dependency change, list the files from the patched build copy and
search them, then update `FILE-NOTICES`, including its review line:

```sh
cd tsnet/build/tmp/buildGoArm64/go   # left by the last release build
tags=…   # ts_omit_<name> for each omittedFeatures entry in tsnet/build.gradle.kts, comma-separated
for arch in arm64 amd64; do
  GOOS=android GOARCH=$arch CGO_ENABLED=1 GOTOOLCHAIN=local GOWORK=off go list -mod=readonly -deps \
    -tags="$tags" -f '{{$d := .Dir}}{{range .GoFiles}}{{$d}}/{{.}}
{{end}}{{range .CgoFiles}}{{$d}}/{{.}}
{{end}}{{range .SFiles}}{{$d}}/{{.}}
{{end}}{{range .CFiles}}{{$d}}/{{.}}
{{end}}{{range .HFiles}}{{$d}}/{{.}}
{{end}}{{range .EmbedFiles}}{{$d}}/{{.}}
{{end}}' .
done | sort -u > files.txt
xargs grep -h -i -E 'copyright|spdx|public domain|licen[cs]e that|licen[cs]ed under|licenses/|based on work|special thanks|(derived|adapted|ported|copied|translated) from|translation of' \
  < files.txt | sed -E 's/[0-9]{4}/YYYY/g' | sort | uniq -c | sort -n   # 149 lines; inspect the unusual ones
```

For the C runtime, rebuild one library with `-extldflags=-Wl,--trace` and
check that no new archive member or object appears.

## Updating

For an intentional native dependency change:

1. Run `./gradlew build`: the `verify*NativeNotices` reports list missing and stale modules. Use the
   Android build tags and toolchains for `go list -deps -json`; a host import graph can differ.
2. Run `go mod verify` in `tsnet/src/main/go`, locate each source with `go mod download -json module@version`,
   review its notices and nested directories, and copy notice bytes unedited. Update the inventory.
3. Repeat the [per-file review](#per-file-review) and update `FILE-NOTICES`.
4. Review the patch in `BuildGoLibrary.kt` against the new pin; update its source hash and
   `TAILSCALE-MODIFICATIONS` together. Never just accept a new hash.
5. Recheck Go notices when updating Go, the CRT notices when updating the NDK, and JSpecify's when
   updating JSpecify.

This is an engineering review of what the binaries contain, not legal advice.
