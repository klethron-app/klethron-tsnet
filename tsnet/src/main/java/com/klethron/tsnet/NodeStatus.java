package com.klethron.tsnet;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// A snapshot of a [TailnetNode]'s connection, login progress and assigned IP addresses.
/// Pass it to [TailnetNode#awaitStatus] to wait for a change. Snapshots are immutable.
/// [#toString()] omits login URLs and error text, which may contain private information.
public final class NodeStatus {
    /// Connection and authorization progress.
    public enum State {
        /// Not started, or starting up locally.
        NO_STATE,
        /// Browser authorization is needed. After logout or revocation, call [TailnetNode#login]
        /// to request a new [NodeStatus#loginUrl()].
        NEEDS_LOGIN,
        /// Logged in, waiting for a tailnet administrator to approve the device.
        NEEDS_MACHINE_AUTH,
        /// Logged in, but not connecting.
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

    final long owner;
    final long version;
    private final State state;
    private final @Nullable Revocation revocation;
    private final @Nullable String loginUrl;
    private final List<InetAddress> addresses;
    private final @Nullable String error;

    private NodeStatus(long owner, long version, State state, @Nullable Revocation revocation, @Nullable String loginUrl,
            List<InetAddress> addresses, @Nullable String error) {
        this.owner = owner;
        this.version = version;
        this.state = state;
        this.revocation = revocation;
        this.loginUrl = loginUrl;
        this.addresses = addresses;
        this.error = error;
    }

    /// Parses native "version\nSTATE\nREVOCATION\nloginURL\naddresses\nerror" records; empty fields
    /// are absent.
    static NodeStatus parse(long owner, String record) {
        String[] fields = record.split("\n", -1);
        List<InetAddress> addresses = new ArrayList<>();
        for (String address : fields[4].split(",")) {
            if (address.isEmpty()) continue;
            try {
                addresses.add(InetAddress.getByName(address)); // A literal: never resolved.
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid native address", e);
            }
        }
        return new NodeStatus(owner, Long.parseLong(fields[0]), State.valueOf(fields[1]),
                fields[2].isEmpty() ? null : Revocation.valueOf(fields[2]), fields[3].isEmpty() ? null : fields[3],
                List.copyOf(addresses), fields[5].isEmpty() ? null : fields[5]);
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

    /// The browser authorization URL, or `null` when no authorization is pending. Whoever opens
    /// this URL can claim the node: send it only to the user's browser; never log or share it.
    /// It clears once the new login is saved. Closing before then can lose that login.
    /// A URL can also appear while [State#RUNNING] when renewing an existing login.
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

    @Override
    public String toString() {
        return "NodeStatus{" + state + (revocation == null ? "" : ", " + revocation) + ", addresses=" + addresses
                + (loginUrl == null ? "" : ", loginUrl=<omitted>") + (error == null ? "" : ", error=<omitted>") + "}";
    }
}
