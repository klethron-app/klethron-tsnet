/// Embedded [tsnet](https://github.com/tailscale/tailscale/tree/main/tsnet) for Android.
/// [TailnetNode] manages a Tailscale identity and outbound TCP connections without a VPN;
/// [NodeStatus] reports its state and peers.
@NullMarked
@CheckReturnValue
package com.klethron.tsnet;

import com.google.errorprone.annotations.CheckReturnValue;
import org.jspecify.annotations.NullMarked;
