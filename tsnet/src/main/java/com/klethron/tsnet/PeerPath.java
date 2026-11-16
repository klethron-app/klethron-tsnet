package com.klethron.tsnet;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/// How this node reached a peer when read by [TailnetNode#peerPath], as `tailscale status` reports it.
///
/// @param active whether traffic was recently exchanged; an idle peer's other fields may be stale
/// @param directAddress the peer's UDP endpoint for direct WireGuard traffic, as `ip:port`; empty if none
/// @param peerRelay the [peer relay](https://tailscale.com/kb/1591/peer-relays) in use, as
///     `ip:port:vni:<id>`; empty if none
/// @param derpRegion the code of the peer's home [DERP](https://tailscale.com/kb/1232/derp-servers)
///     region, such as `fra`, which relays traffic without a direct path or peer relay; empty if unknown
/// @param lastHandshake the latest successful WireGuard handshake with the peer; `null` if none
/// @param sentBytes WireGuard bytes sent to the peer since this node started
/// @param receivedBytes WireGuard bytes received from the peer since this node started
public record PeerPath(
        boolean active,
        String directAddress,
        String peerRelay,
        String derpRegion,
        @Nullable Instant lastHandshake,
        long sentBytes,
        long receivedBytes) {
    /// The route traffic currently takes.
    public enum Route {
        /// Peer to peer over UDP, at [#directAddress()].
        DIRECT,
        /// Through another tailnet device, at [#peerRelay()].
        PEER_RELAY,
        /// Through a Tailscale DERP server, in [#derpRegion()].
        DERP,
        /// No recent traffic, or no path is known yet.
        NONE,
    }

    /// Parses a record as node.go's `peerPath` writes it.
    static PeerPath parse(String record) {
        String[] fields = record.split("\t", -1);
        long handshake = Long.parseLong(fields[4]);
        return new PeerPath(
                Boolean.parseBoolean(fields[0]),
                fields[1],
                fields[2],
                fields[3],
                handshake == 0 ? null : Instant.ofEpochMilli(handshake),
                Long.parseLong(fields[5]),
                Long.parseLong(fields[6]));
    }

    /// The active route with `tailscale status` precedence: direct, then peer relay, then DERP.
    public Route route() {
        if (!active) {
            return Route.NONE;
        }
        if (!directAddress.isEmpty()) {
            return Route.DIRECT;
        }
        if (!peerRelay.isEmpty()) {
            return Route.PEER_RELAY;
        }
        return derpRegion.isEmpty() ? Route.NONE : Route.DERP;
    }
}
