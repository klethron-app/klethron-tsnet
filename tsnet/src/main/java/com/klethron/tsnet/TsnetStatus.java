package com.klethron.tsnet;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// An immutable snapshot of a node's backend state. Pass it back to [TsnetNode#awaitStatus] to
/// wait for the next change.
///
/// [#loginUrl()] lets whoever opens it add this node to their tailnet: give it only to the user's
/// browser, never to logs or other apps. [#toString()] omits it, and the text of [#error()].
public final class TsnetStatus {
    /// Backend states of an embedded node.
    public enum State {
        /// Not started, or starting up locally.
        NO_STATE,
        /// The user must log in. [#loginUrl()] is set once control provides a URL after
        /// [TsnetNode#start] or [TsnetNode#login]; after a logout or a [#revocation()], call `login`.
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

    /// How control ended a node's login, so that it reports [State#NEEDS_LOGIN].
    public enum Revocation {
        /// The node key expired: periodically, unless a tailnet administrator disabled key expiry,
        /// or because an administrator expired it. Logging in again renews it, keeping the
        /// device's tailnet identity.
        KEY_EXPIRED,
        /// A tailnet administrator removed the device, or its user or login was deleted. Logging in
        /// again adds it to a tailnet as a new device.
        NODE_REMOVED,
    }

    final long version;
    private final State state;
    private final @Nullable Revocation revocation;
    private final @Nullable String loginUrl;
    private final List<InetAddress> addresses;
    private final @Nullable String error;

    private TsnetStatus(long version, State state, @Nullable Revocation revocation, @Nullable String loginUrl,
            List<InetAddress> addresses, @Nullable String error) {
        this.version = version;
        this.state = state;
        this.revocation = revocation;
        this.loginUrl = loginUrl;
        this.addresses = addresses;
        this.error = error;
    }

    /// Parses native "version\nSTATE\nREVOCATION\nloginURL\naddresses\nerror" records; empty fields
    /// are absent.
    static TsnetStatus parse(String record) {
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
        return new TsnetStatus(Long.parseLong(fields[0]), State.valueOf(fields[1]),
                fields[2].isEmpty() ? null : Revocation.valueOf(fields[2]), fields[3].isEmpty() ? null : fields[3],
                List.copyOf(addresses), fields[5].isEmpty() ? null : fields[5]);
    }

    /// The node's backend state.
    public State state() {
        return state;
    }

    /// Why the node needs to log in, if control ended its login; `null` in other
    /// states, before the first login and after [TsnetNode#logout]. It stays set, also while
    /// [#loginUrl()] is, until a new login through [TsnetNode#login] completes. A node started
    /// after its key expired may just ask to log in, without a revocation.
    public @Nullable Revocation revocation() {
        return revocation;
    }

    /// The HTTP(S) URL at which the user can log in, from when control provides it until the login
    /// completes. It is cleared once the login is saved in the state directory: close the node only
    /// after that, or a restarted node asks for another login. Usually the state is then
    /// [State#NEEDS_LOGIN]; while a logged-in node re-authenticates after [TsnetNode#login], it may
    /// be any logged-in state.
    public @Nullable String loginUrl() {
        return loginUrl;
    }

    /// This node's Tailscale addresses, IPv4 first; empty until the node has joined a tailnet.
    public List<InetAddress> addresses() {
        return addresses;
    }

    /// The latest error that the Tailscale backend reported to its user, such as control rejecting
    /// a login, until the node is next [State#RUNNING]; `null` if there was none. The text comes
    /// from upstream or control, is limited to 256 printable ASCII characters (others become
    /// `?`) and may change between versions: show it to the user, but neither parse nor log it.
    public @Nullable String error() {
        return error;
    }

    @Override
    public String toString() {
        return "TsnetStatus{" + state + (revocation == null ? "" : ", " + revocation) + ", addresses=" + addresses
                + (loginUrl == null ? "" : ", loginUrl=<omitted>") + (error == null ? "" : ", error=<omitted>") + "}";
    }
}
