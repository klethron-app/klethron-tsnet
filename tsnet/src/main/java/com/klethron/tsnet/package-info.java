/// Java bindings for Tailscale's [tsnet](https://github.com/tailscale/tailscale/tree/main/tsnet),
/// embedded in the app's process. A node is a Tailscale device with its own keys and a userspace
/// network stack; it needs neither the Tailscale app nor a VPN. [TailnetNode] starts, authorizes
/// and closes a node and opens outbound TCP connections through it; [NodeStatus] is a snapshot of
/// its state.
@NullMarked
@CheckReturnValue
package com.klethron.tsnet;

import com.google.errorprone.annotations.CheckReturnValue;
import org.jspecify.annotations.NullMarked;
