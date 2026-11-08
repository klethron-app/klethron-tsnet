# Third-party notices

Tailscale is pinned to `503010831ecd44ab3d86864e5e65ff9f8b969d88` and uses BSD-3-Clause.
Its licence and patent notice are included under `tsnet/src/main/assets/klethron-tsnet/licenses/`.
Go's runtime licence is included there as well.

The Java API depends on `org.jspecify:jspecify:1.0.1` (Apache-2.0). It is a transitive Maven dependency,
not bundled classes inside the AAR. Its upstream [v1.0.1 licence](https://github.com/jspecify/jspecify/blob/v1.0.1/LICENSE)
is included as `JSPECIFY-LICENSE` alongside the native notices for consumers packaging the annotations.

`go.mod` and `go.sum` record dependencies, including dependencies used only on other platforms or by
upstream tests. They are not an exact inventory of code linked into either Android binary.
Before a public binary release, generate and review the Android-linked dependency licences and any
embedded resources and ship their full notices. The initial CI artifacts are development scaffolding;
no Maven binary release is configured. See `PLAN.md`.
