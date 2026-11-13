# TODO

## Multiple DNS addresses

Following [Haven #691](https://github.com/GlassHaven/Haven/issues/691), resolve all candidates once
and try later addresses when the first is unreachable. Upstream `netx.RaceDial` can race the
`NetstackDialTCP` attempts; normalize and check every candidate with `UseNetstackForIP` first.
Preserve peer-name resolution, the caller's deadline and revocation cancellation.

- Test an unreachable first answer followed by a working one, including IPv4/IPv6 fallback.
- Test mixed tailnet and non-tailnet answers without device-network fallback.
- Verify cancellation closes losing and late connections; cover peer names with MagicDNS disabled.
- Preserve both Service VIP address families: the fast name lookup also overwrites extra DNS records
  with the same name ([upstream PR #22292](https://github.com/tailscale/tailscale/pull/22292)).

## Tailnet Lock

Authority history uses caller-owned `StateStore` callbacks, including upstream compaction and
cleanup on logout. Local tests cover restart, unsigned-peer filtering, compaction, corrupt state
and storage failures. The Java API exposes no Tailnet Lock status or signing operations.

- Verify joining a live locked tailnet, rejection of unsigned peers, key rotation and restart.
- Determine what status callers need to diagnose a node awaiting a trusted device's signature.
