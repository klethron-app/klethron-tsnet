package com.klethron.tsnet;

import android.net.InetAddresses;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// A snapshot of a [TailnetNode]'s connection, login progress, account, assigned IP addresses and
/// peers. Pass it to [TailnetNode#awaitStatus] to wait for a change. Snapshots are immutable.
/// [#toString()] omits login URLs, error text, names and peers, which may contain private information.
public final class NodeStatus {
    /// Connection and authorization progress.
    public enum State {
        /// Starting up locally, also briefly after logout.
        NO_STATE,
        /// Browser authorization is needed. After logout or revocation, call [TailnetNode#login]
        /// to request a new [NodeStatus#loginUrl()].
        NEEDS_LOGIN,
        /// Logged in, waiting for a tailnet administrator to approve the device.
        NEEDS_MACHINE_AUTH,
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

    /// Another device in this node's tailnet, to show for selection and pass to [TailnetNode#dial].
    ///
    /// @param dnsName the [MagicDNS](https://tailscale.com/kb/1081/magicdns) name without a trailing
    ///     dot, such as `host.tailnet-name.ts.net`. Its first label is the device's machine name.
    /// @param os the operating system the device reports, such as `linux`, `macOS`, `windows`, `iOS`
    ///     or `android`, or empty if unknown
    /// @param online whether Tailscale reports the device as connected; `false` if unknown
    /// @param addresses the device's Tailscale IP addresses, IPv4 first
    /// @param sshHostKeys the device's SSH host keys in OpenSSH public key format, such as
    ///     `ssh-ed25519 AAAA…`, if it runs [Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh);
    ///     otherwise empty. Tailscale's control server distributes them: accept them for [#dnsName()]
    ///     and [#addresses()] instead of asking the user to trust an unknown key, as `tailscale ssh` does.
    public record Peer(String dnsName, String os, boolean online, List<InetAddress> addresses, List<String> sshHostKeys) {
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
    private final @Nullable String tailnet;
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
        tailnet = emptyToNull(fields[7]);
        loginName = emptyToNull(fields[8]);
        peers = Arrays.stream(fields, 9, fields.length).map(line -> {
            String[] peer = line.split("\t", -1);
            return new Peer(peer[0], peer[1], Boolean.parseBoolean(peer[2]), addresses(peer[3]), list(peer[4]));
        }).toList();
    }

    private static @Nullable String emptyToNull(String field) {
        return field.isEmpty() ? null : field;
    }

    private static List<String> list(String commaSeparated) {
        return commaSeparated.isEmpty() ? List.of() : List.of(commaSeparated.split(","));
    }

    private static List<InetAddress> addresses(String commaSeparated) {
        return list(commaSeparated).stream().map(InetAddresses::parseNumericAddress).toList();
    }

    /// The node's connection and authorization state.
    public State state() {
        return state;
    }

    /// Why access ended, or `null` before first login, after logout, and outside [State#NEEDS_LOGIN].
    /// Remains set while requesting a new login. Expiry while the node was closed may only report
    /// [State#NEEDS_LOGIN], without a reason.
    public @Nullable Revocation revocation() {
        return revocation;
    }

    /// The browser authorization URL, or `null` when none is pending; it can also appear while
    /// [State#RUNNING] when renewing a login. Whoever opens it can claim the node: send it only to
    /// the user's browser, never log or share it. It clears once the new login is saved; closing
    /// before then can lose that login.
    public @Nullable String loginUrl() {
        return loginUrl;
    }

    /// This node's Tailscale IP addresses, IPv4 first; empty before joining. Addresses alone do
    /// not imply access: they may remain after key expiry. Check [#state()].
    public List<InetAddress> addresses() {
        return addresses;
    }

    /// The latest connection or login error, or `null` when none is reported. Cleared on reaching
    /// [State#RUNNING]. Show this text to the user, but do not parse or log it; it may contain private
    /// information and its wording can change.
    public @Nullable String error() {
        return error;
    }

    /// This node's [MagicDNS](https://tailscale.com/kb/1081/magicdns) name without a trailing dot,
    /// such as `phone.tailnet-name.ts.net`; `null` before joining and after logout. Tailscale may adjust
    /// the requested hostname, and administrators can rename the device. The part after the first
    /// label is the tailnet's: a saved [Peer#dnsName()] with another suffix is in another tailnet.
    /// Like [#tailnet()] and [#loginName()], it remains after key expiry and can change with the next
    /// login, which may choose another account or tailnet.
    public @Nullable String dnsName() {
        return dnsName;
    }

    /// The name of this node's tailnet, such as `example.com`; `null` before joining and after logout.
    public @Nullable String tailnet() {
        return tailnet;
    }

    /// The login name of the user who signed in this node, such as `alice@example.com`; `null` before
    /// joining and after logout.
    public @Nullable String loginName() {
        return loginName;
    }

    /// The other devices in this node's network map, sorted by [Peer#dnsName()]; empty before joining
    /// and after logout. Omits devices that may only connect to this node and Mullvad exit nodes.
    /// Peers can remain listed while not [State#RUNNING], as after key expiry, but are unreachable.
    /// Each change, including a peer going online or offline, is a status change.
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
