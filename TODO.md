# TODO

## Tailnet Lock

Upstream support is compiled into both release ABIs, but locked-tailnet operation is unverified.
The Java API exposes no Tailnet Lock status or signing operations.

- Verify joining a locked tailnet, rejection of unsigned peers, key rotation and restart.
- Determine what status callers need to diagnose a node awaiting a trusted device's signature.
- Investigate caller-owned persistence for the authority history (`tka-profiles/`), which bypasses
  `ipn.StateStore`. With no var-root, upstream uses in-memory authority history, which is lost on
  restart. Route it through `StateStore` without filesystem access before claiming durable support.
