# Klethron tsnet

- The package is under development; backward API compatibility is not a concern.
- Minimal code and simplicity are core values. Add abstractions only when a concrete caller needs them.
- Binary size matters. Measure both ABI libraries and the compressed AAR when native dependencies change.
- Follow the boy scout principle: continuously improve code when something is noticed, even when it is
  unrelated to the task at hand. Keep improvements small, reviewable and verified.
- Keep README.md's status and UPSTREAM.md current. Do not describe scaffolding as working networking.
- Java package: `com.klethron.tsnet`. Maven coordinates: `com.klethron:tsnet`.
- Android 14/API 34 minimum. Ship only arm64-v8a and x86_64, with 16 KiB ELF alignment.
- Gradle is the build entry point. Keep native tasks incremental and configuration-cache compatible.
- Pin dependencies and toolchains. Never resolve a moving Tailscale branch during normal builds.
- Keep the Java/JNI/Go boundary narrow. No SSH, UI, Android services or app lifecycle policy in the library.
- No subprocesses, downloaded executables, hidden APIs or device-wide VPN in shipped code.
- Never log, back up or transfer credentials. Node state belongs in caller-owned no-backup storage.
- Verify changes with `./gradlew build`; use `ANDROID_SERIAL=<serial> ./gradlew :tsnet:connectedDebugAndroidTest`
  for JNI changes. Record device/API/page-size coverage and test counts from JUnit XML in the commit message.
- Keep third-party licence notices with distributed binaries. Do not publish a release until that inventory
  and runtime verification are complete.
