package com.klethron.tsnet;

import android.os.SystemClock;
import android.util.Log;

/// Fixed diagnostic labels only. Native callbacks enter synchronously; Java timestamps before formatting.
final class Timing {
    static final boolean ENABLED = true;
    private static final String[] PHASES = {
        "native_start", "native_close", "control_login", "control_keys", "control_registration",
        "control_connect", "control_http", "control_https", "control_map_request", "control_map_received",
        "netcheck", "stun_wait", "portmap_wait", "relay_connect", "relay_hint", "relay_selected", "rebind",
        "native_dial", "running_wait", "peer_lookup", "peer_tcp", "state_initializing", "state_starting",
        "state_running", "state_stopped", "state_login", "state_approval", "store_read", "store_write"
    };
    private static final String[] EVENTS = {"begin", "end", "instant", "link"};

    private Timing() {}

    static void event(String scope, long id, long span, String phase, String event, String result) {
        long timestamp = SystemClock.elapsedRealtimeNanos();
        write(timestamp, scope, id, span, phase, event, result.isEmpty() ? "" : " result=" + result);
    }

    static void link(String scope, long id, String relatedScope, long relatedId) {
        long timestamp = SystemClock.elapsedRealtimeNanos();
        write(timestamp, scope, id, 0, "identity", "link",
                " related_scope=" + relatedScope + " related_id=" + relatedId);
    }

    static long identity(Object value) {
        return Integer.toUnsignedLong(System.identityHashCode(value));
    }

    private static void write(long timestamp, String scope, long id, long span, String phase, String event, String suffix) {
        Log.i("ConnectTiming", "v=1 source=tsnet scope=" + scope + " id=" + id + " span=" + span
                + " phase=" + phase + " event=" + event + " t_ns=" + timestamp + suffix);
    }

    // Called only by the debug JNI bridge; phase/event indices come from the fixed Go allowlist.
    private static void nativeEvent(long node, long span, int phase, int event) {
        if (event == 3) {
            link("tsnet_node", node, "tsnet_handle", span);
        } else if (event == 4) {
            link("tsnet_dial", node, "fd", span);
        } else if (phase >= 0 && phase < PHASES.length && event >= 0 && event < 3) {
            event("tsnet_node", node, span, PHASES[phase], EVENTS[event], "");
        }
    }

    static native void initialize();
}
