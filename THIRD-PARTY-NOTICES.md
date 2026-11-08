# Third-party notices

Tailscale is pinned to `503010831ecd44ab3d86864e5e65ff9f8b969d88` and uses BSD-3-Clause.
Its licence and patent notice are included under `tsnet/src/main/assets/klethron-tsnet/licenses/`.
Go's runtime licence is included there as well.

The Java API depends on `org.jspecify:jspecify:1.0.1` (Apache-2.0). It is a transitive Maven dependency,
not bundled classes inside the AAR. Its upstream [v1.0.1 licence](https://github.com/jspecify/jspecify/blob/v1.0.1/LICENSE)
is included as `JSPECIFY-LICENSE` alongside the native notices for consumers packaging the annotations.

## Native module inventory

[`GO-MODULES.tsv`](tsnet/src/main/assets/klethron-tsnet/licenses/GO-MODULES.tsv) records the 26 dependency
modules reported by `go version -m` for both Android ABIs in both build variants: module path, pinned
version, Go module checksum, and paths to the included notices. Paths are relative to the inventory.
The inventory and full notice texts ship in the AAR under `assets/klethron-tsnet/licenses/`.

Debug libraries also link an in-process test control server and DERP/STUN server for instrumentation.
[`GO-MODULES-DEBUG.tsv`](tsnet/src/debug/assets/klethron-tsnet/licenses/GO-MODULES-DEBUG.tsv) records the
five modules these add (31 in total), with notices copied verbatim from the verified module cache. They are
packaged only in the debug AAR. The release AAR and its 26-module inventory are unchanged.

`TAILSCALE-MODIFICATIONS` records three local patches. Two make `ts_omit_logtail` skip log configuration
and socket-log directory initialization; the third keeps the node key after a failed logout.
`BuildGoLibrary` verifies the upstream modules and all three original source-file hashes, then patches an isolated copy using a temporary `./tailscale` module
replacement. The shared module cache is unchanged. Binary metadata records this replacement and
the original Tailscale version; its inventory checksum identifies the **unmodified upstream module**,
not the patched source. Other dependencies retain their binary-reported checksums.

Notices were copied verbatim from the checksum-verified Go module cache. Each module's root licence
and any root patent notice are included. The compression dependency additionally includes the nested
licences for `internal/snapref` and `zstd/internal/xxhash`, both in the Android import graph. Its root
licence also contains notices for code outside that graph; those upstream notices remain intact.
The existing Go and Tailscale notices are retained, including Go notices for Tailscale's Go-derived
`tempfork/heap`, `tempfork/acme`, and `tempfork/httprec` sources.

`./gradlew build` runs `:tsnet:verifyReleaseNativeNotices` and `:tsnet:verifyDebugNativeNotices`;
the debug check expects the union of both inventories.
These inspect each AAR's actual libraries, reject missing/stale modules, version/checksum drift and
unexpected module replacements, check the pinned Go runtime version, and compare every packaged notice with
its checked-in source. Reports are in `tsnet/build/reports/native/notices-{release,debug}.tsv`.
Only the documented `tailscale.com => ./tailscale (devel)` replacement is accepted. Normal verification
does not generate or download notices and does not accept new modules automatically.

For an intentional native dependency change:

1. Build both variants and inspect `go version -m` on all four libraries. Use the Android build tags
   and toolchains when inspecting `go list -deps -json`; a host import graph can differ.
2. Run `go mod verify` in `tsnet/src/main/go`. Locate each pinned source with
   `go mod download -json module@version`, review its notices and relevant nested directories, and copy
   notice bytes without editing them. Update the inventory's versions, sums and relative notice paths.
3. Re-review the patches in `BuildGoLibrary.kt` against any new upstream pin; update the source hashes
   and `TAILSCALE-MODIFICATIONS` together. Removing or changing a patch changes the Gradle task
   implementation fingerprint, forcing native rebuilds. Keep the replacement check in sync if the
   patches are retired. Do not merely accept a new source hash without reviewing the patched paths.
4. Review source-level attributions, generated data, embedded resources and native toolchain code
   separately. Recheck Go runtime notices when updating Go, and JSpecify notices when updating JSpecify.
5. Run `./gradlew build`, review both notice reports, and record the ABI library and complete AAR sizes.

This is a **Go module build-metadata inventory**, not a symbol-level or complete third-party audit.
`go.mod` also contains modules used only on other platforms or by tests, while build metadata can
include modules whose code is later removed by the linker. It does not inventory C/NDK runtime code,
per-file attribution, generated tables, or non-Go resources. The current Android import graphs report
only Tailscale version/toolchain text files and `net/dnsfallback/dns-fallback-servers.json` as embedded
files; this is an import-graph observation, not proof of which resources survive linking.

Complete that remaining audit and runtime verification before a public binary release. No Maven binary
release is configured. See [PLAN.md](PLAN.md).
