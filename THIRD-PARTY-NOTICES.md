# Third-party notices

Notices ship in the AAR under `assets/klethron-tsnet/licenses/`, from
`tsnet/src/main/assets/klethron-tsnet/licenses/` (both variants) and `tsnet/src/debug/…` (debug only):

- Tailscale (BSD-3-Clause), pinned to `62d055f4c38b610d63a8cee7fa4a7e65b01564eb`: licence, patent notice
  and `TAILSCALE-MODIFICATIONS`, which documents the one local patch.
- Go's runtime licence and patent notice, also covering Tailscale's Go-derived `tempfork` sources.
- JSpecify 1.0.1 (Apache-2.0): a transitive Maven dependency, not bundled; its
  [licence](https://github.com/jspecify/jspecify/blob/v1.0.1/LICENSE) is included as `JSPECIFY-LICENSE`.
- [`GO-MODULES.tsv`](tsnet/src/main/assets/klethron-tsnet/licenses/GO-MODULES.tsv): the 26 modules that
  `go version -m` reports for both ABIs and variants, with version, checksum and notice paths.
  [`GO-MODULES-DEBUG.tsv`](tsnet/src/debug/assets/klethron-tsnet/licenses/GO-MODULES-DEBUG.tsv) adds the
  five modules of the debug-only test control and DERP/STUN servers.

Notices were copied verbatim from the checksum-verified module cache: each module's root licence and
patent notice, plus the nested `internal/snapref` and `zstd/internal/xxhash` licences of the compression
module, which are in the Android import graph.

The patch is applied to an isolated copy through a temporary `tailscale.com => ./tailscale` replacement.
Binary metadata records that replacement, so the inventory's Tailscale checksum identifies the
**unmodified upstream module**, not the patched source.

`./gradlew build` runs `verifyReleaseNativeNotices` and `verifyDebugNativeNotices`. They inspect each AAR's
libraries and fail on missing or stale modules, version or checksum drift, any other replacement, a
different Go version, or a packaged notice that differs from its checked-in source. Reports are in
`tsnet/build/reports/native/notices-{release,debug}.tsv`. Nothing is generated or downloaded.

For an intentional native dependency change:

1. Build both variants and inspect `go version -m` on all four libraries. Use the Android build tags and
   toolchains for `go list -deps -json`; a host import graph can differ.
2. Run `go mod verify` in `tsnet/src/main/go`, locate each source with `go mod download -json module@version`,
   review its notices and nested directories, and copy notice bytes unedited. Update the inventory.
3. Review the patch in `BuildGoLibrary.kt` against the new pin; update its source hash and
   `TAILSCALE-MODIFICATIONS` together. Never just accept a new hash.
4. Recheck Go notices when updating Go, and JSpecify's when updating JSpecify.
5. Run `./gradlew build` and record the ABI library and AAR sizes.

This is a **Go module build-metadata inventory**, not a complete third-party audit. It does not cover
C/NDK runtime code, per-file attribution, generated tables or non-Go resources; the Android import graphs
embed only Tailscale version/toolchain text and `net/dnsfallback/dns-fallback-servers.json`. Complete that
audit and runtime verification before a public binary release. See [PLAN.md](PLAN.md).
