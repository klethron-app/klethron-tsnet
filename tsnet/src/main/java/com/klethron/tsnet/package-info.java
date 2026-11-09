/// Connect an Android app to a Tailscale private network (a tailnet) and open TCP connections to
/// its devices. [TailnetNode] manages the app's network identity; [NodeStatus] reports login and
/// connection progress. The library uses [tsnet](https://github.com/tailscale/tailscale/tree/main/tsnet)
/// inside the app's process, without a device-wide VPN.
@NullMarked
@CheckReturnValue
package com.klethron.tsnet;

import com.google.errorprone.annotations.CheckReturnValue;
import org.jspecify.annotations.NullMarked;
