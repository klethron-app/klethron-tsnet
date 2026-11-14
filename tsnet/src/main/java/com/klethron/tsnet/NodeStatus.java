package com.klethron.tsnet;

import android.net.InetAddresses;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// An immutable snapshot of a [TailnetNode]. Pass it to [TailnetNode#awaitStatus] to wait for changes.
/// [#toString()] omits login URLs, error text, names and peers for privacy.
public final class NodeStatus {
    /// Connection and authorization progress.
    public enum State {
        /// Starting up locally, also briefly after logout.
        INITIALIZING,
        /// Browser authorization is needed. After logout or revocation, call [TailnetNode#login]
        /// to request a new [NodeStatus#loginUrl()].
        NEEDS_LOGIN,
        /// Logged in, waiting for a tailnet administrator to approve the device.
        NEEDS_APPROVAL,
        /// Logged in, but not connecting, as after a failed [TailnetNode#logout].
        STOPPED,
        /// Logged in and connecting.
        STARTING,
        /// Connected to the tailnet.
        RUNNING,
    }

    /// Why Tailscale ended this node's access, requiring another login.
    public enum Revocation {
        /// The login expired on schedule or at an administrator's request. Logging in renews it
        /// while keeping the device's identity.
        KEY_EXPIRED,
        /// The device or its account was removed. Logging in adds it as a new device.
        NODE_REMOVED,
    }

    /// A tailnet device; pass its name or address to [TailnetNode#connect].
    ///
    /// @param dnsName the [MagicDNS](https://tailscale.com/kb/1081/magicdns) name, such as
    ///     `host.tailnet-name.ts.net`, without a trailing dot; the first label is the machine name
    /// @param operatingSystem the reported operating system, such as `linux` or `android`; empty if unknown
    /// @param online whether Tailscale reports the device as connected; `false` if unknown
    /// @param addresses the device's Tailscale IP addresses, IPv4 first
    /// @param sshHostKeys [Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh) host keys from
    ///     control, in OpenSSH format; empty without Tailscale SSH. Accept them for [#dnsName()] and
    ///     [#addresses()] without trust-on-first-use.
    public record Peer(String dnsName, String operatingSystem, boolean online, List<InetAddress> addresses, List<String> sshHostKeys) {
        public Peer {
            addresses = List.copyOf(addresses);
            sshHostKeys = List.copyOf(sshHostKeys);
        }
    }

    final long owner;
    final long version;
    private final State state;
    private final @Nullable Revocation revocation;
    private final @Nullable String loginUrl;
    private final List<InetAddress> addresses;
    private final @Nullable String error;
    private final @Nullable String dnsName;
    private final @Nullable String tailnetName;
    private final @Nullable String loginName;
    private final List<Peer> peers;

    /// Parses a record as node.go's `nodeStatus.record` writes it; empty fields are absent.
    NodeStatus(long owner, String record) {
        String[] fields = record.split("\n", -1);
        this.owner = owner;
        version = Long.parseLong(fields[0]);
        state = State.valueOf(fields[1]);
        revocation = fields[2].isEmpty() ? null : Revocation.valueOf(fields[2]);
        loginUrl = emptyToNull(fields[3]);
        addresses = addresses(fields[4]);
        error = emptyToNull(fields[5]);
        dnsName = emptyToNull(fields[6]);
        tailnetName = emptyToNull(fields[7]);
        loginName = emptyToNull(fields[8]);
        peers = Arrays.stream(fields, 9, fields.length).map(line -> {
            String[] peer = line.split("\t", -1);
            return new Peer(peer[0], peer[1], Boolean.parseBoolean(peer[2]), addresses(peer[3]),
                    peer[4].isEmpty() ? List.of() : List.of(peer[4].split(",")));
        }).toList();
    }

    private static @Nullable String emptyToNull(String field) {
        return field.isEmpty() ? null : field;
    }

    private static List<InetAddress> addresses(String commaSeparated) {
        return commaSeparated.isEmpty() ? List.of()
                : Arrays.stream(commaSeparated.split(",")).map(InetAddresses::parseNumericAddress).toList();
    }

    /// The node's connection and authorization state.
    public State state() {
        return state;
    }

    /// Why access ended; `null` before first login, after logout, and outside [State#NEEDS_LOGIN]. Remains
    /// set during re-login. Expiry while closed may report [State#NEEDS_LOGIN] without a reason.
    /// The reason can arrive after the state change.
    public @Nullable Revocation revocation() {
        return revocation;
    }

    /// HTTPS browser authorization URL on tailscale.com or a subdomain, or `null` when none is pending.
    /// Also used while [State#RUNNING]
    /// to renew a login. Whoever opens it can claim the node: send it only to the user's browser,
    /// never log or share it. Wait for it to clear before closing, so the new login reaches [StateStore].
    public @Nullable String loginUrl() {
        return loginUrl;
    }

    /// This node's Tailscale IP addresses, IPv4 first; empty before joining. Addresses alone do
    /// not imply access: they may remain after key expiry. Check [#state()].
    public List<InetAddress> addresses() {
        return addresses;
    }

    /// The latest connection or login error; `null` if absent or cleared by reaching [State#RUNNING].
    /// Show it to the user, but do not parse or log it: wording can change and include private data.
    public @Nullable String error() {
        return error;
    }

    /// This node's [MagicDNS](https://tailscale.com/kb/1081/magicdns) name without a trailing dot,
    /// e.g. `phone.tailnet-name.ts.net`; `null` before joining and after logout. Tailscale or an
    /// administrator can rename it. Like [#tailnetName()] and [#loginName()], it survives key expiry
    /// and can change with the account or tailnet.
    public @Nullable String dnsName() {
        return dnsName;
    }

    /// This node's tailnet name, e.g. `example.com`; `null` before joining and after logout.
    public @Nullable String tailnetName() {
        return tailnetName;
    }

    /// The user's login name, e.g. `alice@example.com`; `null` before joining and after logout.
    public @Nullable String loginName() {
        return loginName;
    }

    /// Devices in the network map, sorted by [Peer#dnsName()]; empty before joining and after logout.
    /// Omits devices that may only connect to this node and Mullvad exit nodes. Peers can remain
    /// listed but unreachable outside [State#RUNNING]. Online/offline changes update the snapshot.
    public List<Peer> peers() {
        return peers;
    }

    @Override
    public String toString() {
        return "NodeStatus{" + state + (revocation == null ? "" : ", " + revocation) + ", addresses=" + addresses
                + (loginUrl == null ? "" : ", loginUrl=<omitted>") + (error == null ? "" : ", error=<omitted>")
                + ", peers=" + peers.size() + "}";
    }
}
