# Demo app

A minimal R8-minified consumer of `:tsnet`, used by the device tests and for manual live checks.
It is not a distributable application: release builds are signed with the debug key.

- **Load tsnet** loads the native library and shows the bundled Tailscale version. No network is
  started.
- **Live tailnet test** starts a node against Tailscale's real control plane. Authorize it in the
  browser, then verify a TCP echo from a peer in your tailnet (default port 7070), for example one
  running `socat TCP-LISTEN:7070,fork,reuseaddr EXEC:cat`. Node state stays in the app's no-backup
  storage; closing the screen disconnects and **Start** resumes the saved login.

```sh
./gradlew :demo:installRelease
ANDROID_SERIAL=<serial> ./gradlew :demo:connectedReleaseAndroidTest
```

The device tests contact Tailscale's control plane but never open the login URL. See
[CONTRIBUTING.md](../CONTRIBUTING.md#build-and-test) for the full live-check procedure.
