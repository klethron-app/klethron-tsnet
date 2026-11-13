# Wishes from the Klethron app

Klethron, an SSH terminal for Android, plans to ship this library in an on-demand Play feature module
(`TAILSCALE-PLAN.md` in the Klethron repository). Delete each wish once it ships; declined wishes stay,
saying what Klethron can do instead.

## 1. A stable ID for each peer: declined

Save the chosen peer's name and pin its `sshHostKeys`, or the keys seen on first connect for peers
without Tailscale SSH. Dial the saved name and require the same keys; prompt before accepting changes.

This detects a name moving to another device and follows address changes. A stable ID would also
follow renames, but would break saved hosts when a server is reinstalled or re-added. That tradeoff
does not justify another identifier in the API.

## 2. Load the native library after an on-demand install (only if needed)

Klethron needs to test loading after a Play feature module install, without restarting the app.
`TailnetNode` always calls `System.loadLibrary("klethron_tsnet")`; the running class loader may not
find the new split even after the caller loads it with `SplitInstallHelper.loadLibrary`.

If that fails, let the initializer probe an already-loaded native symbol before rethrowing
`UnsatisfiedLinkError`. The failure remains unverified on API 37; wait for the staged-install test
before changing the loader.
