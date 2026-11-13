# TODO

## Tailnet Lock

Authority history uses caller-owned `StateStore` callbacks, including upstream compaction and
cleanup on logout. Local tests cover restart, unsigned-peer filtering, compaction, corrupt state
and storage failures. The Java API exposes no Tailnet Lock status or signing operations.

- Verify joining a live locked tailnet, rejection of unsigned peers, key rotation and restart.
- Determine what status callers need to diagnose a node awaiting a trusted device's signature.
