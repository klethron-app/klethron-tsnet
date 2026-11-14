package com.klethron.tsnet;

import androidx.annotation.MainThread;
import java.net.InetAddress;

/// App policy and UI for local-network permission, supplied once to [TailnetNode#start].
/// Both methods run on the main thread after a direct peer UDP permission failure on the current LAN,
/// and only while API 37's `ACCESS_LOCAL_NETWORK` permission is missing for a target-37+ app.
/// Exceptions follow normal main-thread rules. The library never requests permission itself.
public interface LocalNetworkPermissionHandler {
    /// Whether `peer`, a Tailscale IP address, is currently selected, its terminal is visible, and
    /// the offer has not been dismissed or already shown. Read the app's current state here;
    /// queued failures for an old selection must return `false`.
    @MainThread
    boolean canOffer(InetAddress peer);

    /// Shows the rationale or requests permission. Called immediately after [#canOffer] returns
    /// `true`, with the same address. Update the app's offer state before returning so repeated
    /// failures do not present duplicate offers. The app owns dismissal persistence and retries.
    @MainThread
    void offer(InetAddress peer);
}
