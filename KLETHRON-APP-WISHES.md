# Wishes from the Klethron app

Klethron, an SSH terminal for Android, plans to ship this library in an on-demand Play feature module
(`TAILSCALE-PLAN.md` in the Klethron repository). That plan was reviewed against 0.6.0. These changes
would let Klethron integrate without workarounds. They are ordered by importance: the second affects
every open session, and the last two are packaging details. The first is declined and says what Klethron
can do instead. Delete each entry once it ships.

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

## 2. End open connections when tailnet access ends

**Wanted:** when a node leaves `RUNNING` for `NEEDS_LOGIN` (logout, key expiry or removal), end every
connection it dialed, as `close()` already does, so callers read end of stream. Keep connections open
through transient states such as `STARTING` during network changes.

**Why:** today they stay open but silent. `DialTest.listsPeersAndTheirChanges` writes after `logout` and
gets neither an echo nor end of stream, because `relay` ties each connection to the node's lifetime
(`n.ctx`), not to its login. An SSH session over such a connection freezes until keep-alives give up,
which can take minutes, or never if keep-alives are off. A user who taps "Log out" expects their sessions
to end, and nothing should stay connected after access is revoked.

Each caller could track its descriptors and close them on the status change. But every caller would
repeat that code and race status delivery, while the library knows exactly when access ended. Expiry and
removal are untested here and probably behave like logout.

**Sketch:** derive each relay's context from a per-login context, which `publish` cancels on the
transition to `NeedsLogin`.

## 3. Do not export JSpecify at runtime

**Wanted:** declare `org.jspecify:jspecify` as `compileOnly` instead of `api`, like `androidx.annotation`
and `error_prone_annotations`.

**Why:** `api` puts JSpecify on every consumer's runtime classpath, and into its APK unless R8 removes it.
Klethron allows a single general-purpose third-party jar at runtime (BouncyCastle) and declares JSpecify
`compileOnly` in its own modules, so it has to exclude it from this dependency. The reasoning in
`tsnet/build.gradle.kts` for the other annotation artifacts probably applies: tools match these
annotations by name in class files. Before switching, check with the minified demo and a Kotlin consumer
that Kotlin still sees nullability and that `javac -Werror` stays quiet without the jar. Then note in the
README that consumers who want nullness checking add JSpecify `compileOnly` themselves.

## 4. Load the native library after an on-demand install (only if needed)

**Wanted:** only if Klethron's staged-install test shows the need: a way to use `TailnetNode` when
`System.loadLibrary("klethron_tsnet")` cannot find a library the caller has already loaded. For example,
the static initializer could catch `UnsatisfiedLinkError`, try a native call such as
`nativeTailscaleVersion()`, and rethrow the original error only if that call fails too.

**Why:** Klethron delivers this library in a Play feature module that is installed while the app runs.
Play's documentation tells apps to load such libraries with `SplitInstallHelper.loadLibrary` after
`SplitCompat` installs the module, because the running process's class loader may not search the new
split's native directory. `TailnetNode`'s static initializer always calls `System.loadLibrary`, so a
caller cannot substitute the documented call. Whether this fails on API 37 is unverified. Klethron will
test loading right after a staged install, without a restart, before this is worth changing.
