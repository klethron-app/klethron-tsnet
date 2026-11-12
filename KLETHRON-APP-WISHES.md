# Wishes from the Klethron app

Klethron, an SSH terminal for Android, plans to ship this library in an on-demand Play feature module
(`TAILSCALE-PLAN.md` in the Klethron repository). Delete each wish once it ships; declined wishes stay,
saying what Klethron can do instead.

## 1. A stable ID for each peer: declined

**Instead:** pin host keys as `known_hosts` does. When the user picks a peer, save its name and its
`sshHostKeys`, or the keys seen on first connect for peers without Tailscale SSH. Later, dial the saved
name and require the same keys, with an explicit prompt to accept changed ones.

**Why:**

- The silent case exists only if a caller accepts whatever keys the current network map lists for a saved
  name. With pinned keys, a name that moves to another device fails with a key mismatch, as when DNS is
  pointed at a new server.
- Which device holds a name is the tailnet owner's decision, made through control and the admin console,
  which this library already trusts for addresses and host keys. `tailscale ssh` also trusts whichever
  device holds the name now.
- The other changes already fail visibly or do not matter: a renamed device's old name no longer resolves,
  and dialing by name follows address changes.
- Saving by ID has its own cost: a reinstalled or re-added server gets a new ID, so saved hosts would break
  even when the user wants the name to keep working.
- What remains, following a device through a rename, is not worth more API.

## 2. Load the native library after an on-demand install (only if needed)

**Wanted:** a way to use `TailnetNode` when `System.loadLibrary("klethron_tsnet")` cannot find a library
the caller has already loaded. For example, the static initializer could catch `UnsatisfiedLinkError`,
try a native call such as `tailscaleVersion()`, and rethrow the original error only if that call fails too.

**Why:** Klethron delivers this library in a Play feature module that is installed while the app runs.
Play's documentation tells apps to load such libraries with `SplitInstallHelper.loadLibrary` after
`SplitCompat` installs the module, because the running process's class loader may not search the new
split's native directory. `TailnetNode`'s static initializer always calls `System.loadLibrary`, so a
caller cannot substitute the documented call. Whether this fails on API 37 is unverified. Klethron will
test loading right after a staged install, without a restart, before this is worth changing.
