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
/// browser, never to logs or other apps. [#toString()] omits it.
public final class TsnetStatus {
    /// Backend states of an embedded node.
    public enum State {
        /// Not started, or starting up locally.
        NO_STATE,
        /// The user must log in. [#loginUrl()] is set once control provides a URL after
        /// [TsnetNode#start] or [TsnetNode#login]; after a logout or key expiry, call `login`.
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

    final long version;
    private final State state;
    private final @Nullable String loginUrl;
    private final List<InetAddress> addresses;

    private TsnetStatus(long version, State state, @Nullable String loginUrl, List<InetAddress> addresses) {
        this.version = version;
        this.state = state;
        this.loginUrl = loginUrl;
        this.addresses = addresses;
    }

    /// Parses native "version\nSTATE\nloginURL\naddresses" records; empty fields are absent.
    static TsnetStatus parse(String record) {
        String[] fields = record.split("\n", -1);
        List<InetAddress> addresses = new ArrayList<>();
        for (String address : fields[3].split(",")) {
            if (address.isEmpty()) continue;
            try {
                addresses.add(InetAddress.getByName(address)); // A literal: never resolved.
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid native address", e);
            }
        }
        return new TsnetStatus(Long.parseLong(fields[0]), State.valueOf(fields[1]),
                fields[2].isEmpty() ? null : fields[2], List.copyOf(addresses));
    }

    public State state() {
        return state;
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

    @Override
    public String toString() {
        return "TsnetStatus{" + state + ", addresses=" + addresses + (loginUrl == null ? "" : ", loginUrl=<omitted>") + "}";
    }
}
