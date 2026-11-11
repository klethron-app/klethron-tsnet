# Wishes from the Klethron app

Klethron, an SSH terminal for Android, plans to ship this library in an on-demand Play feature module
(`TAILSCALE-PLAN.md` in the Klethron repository). That plan was reviewed against 0.6.0. These changes
would let Klethron integrate without workarounds. They are ordered by importance: the first two block
saving hosts, the third affects every open session, and the last two are packaging details. Delete each
entry once it ships.

## 1. A stable ID for each peer

**Wanted:** `NodeStatus.Peer.id()`, the peer's `tailcfg.StableNodeID`. `refreshPeers` already reads it
as `ipnstate.PeerStatus.ID`; it only needs another field in the peer line.

**Why:** Klethron saves a chosen peer as a host and connects to it later, possibly months later. Neither
identifier the API offers today is stable. A MagicDNS name changes when the device is renamed, and
another device can then take it; an administrator can change a device's Tailscale addresses. A saved host
must never silently reach a different machine.

This matters more because the library invites callers to accept `sshHostKeys` without asking the user,
as `tailscale ssh` does. If a saved name moves to another device, that device's host keys come with it,
and the connection would succeed with no warning at all. With a stable ID, Klethron finds the saved peer
in the current network map, dials its current address and accepts only that peer's host keys. A peer
that has vanished fails visibly instead.

## 2. This node's account and tailnet

**Wanted:** this node's own stable ID and MagicDNS name, the tailnet name and the signed-in user's login
name in `NodeStatus`. The `ipnstate.Status` that `refreshPeers` reads already carries them (`Self.ID`,
`Self.DNSName`, `CurrentTailnet.Name`, `User[Self.UserID].LoginName`). A self change (`notify.SelfChange`)
would also need to trigger a refresh. The login name is personal, so `toString()` should omit it, as it
omits login URLs.

**Why:**

- Saved routes belong to an account. After a logout or `NODE_REMOVED`, the user can sign in with a
  different account or tailnet, whose peers may reuse the same names. Klethron must then fail the old
  hosts with an explanation ("this host belongs to tailnet X; you are signed in to Y") rather than
  retarget them or fail without saying why.
- Setup should show who is signed in and the device's actual name, since `start` says Tailscale may
  adjust the requested hostname. The user needs both to find the device in the admin console to approve,
  rename or remove it.

## 3. End open connections when tailnet access ends

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

## 4. Do not export JSpecify at runtime

**Wanted:** declare `org.jspecify:jspecify` as `compileOnly` instead of `api`, like `androidx.annotation`
and `error_prone_annotations`.

**Why:** `api` puts JSpecify on every consumer's runtime classpath, and into its APK unless R8 removes it.
Klethron allows a single general-purpose third-party jar at runtime (BouncyCastle) and declares JSpecify
`compileOnly` in its own modules, so it has to exclude it from this dependency. The reasoning in
`tsnet/build.gradle.kts` for the other annotation artifacts probably applies: tools match these
annotations by name in class files. Before switching, check with the minified demo and a Kotlin consumer
that Kotlin still sees nullability and that `javac -Werror` stays quiet without the jar. Then note in the
README that consumers who want nullness checking add JSpecify `compileOnly` themselves.

## 5. Load the native library after an on-demand install (only if needed)

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
