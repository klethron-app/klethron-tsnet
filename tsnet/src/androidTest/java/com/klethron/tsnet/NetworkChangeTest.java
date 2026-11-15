package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static com.klethron.tsnet.TestSupport.shell;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/// Switches two local nodes from Wi-Fi to cellular. Requires both networks; a VPN may be active.
public final class NetworkChangeTest {
    @Test(timeout = 180000)
    public void rebindsWhenDefaultNetworkChanges() throws Exception {
        ConnectivityManager connectivity = CONTEXT.getSystemService(ConnectivityManager.class);
        assertTrue("The default network must be Wi-Fi", awaitWifiDefault(connectivity));

        // Observe the same physical network as the library.
        LinkedBlockingQueue<String> names = new LinkedBlockingQueue<>();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                String name = properties.getInterfaceName();
                names.add(name == null ? "" : name);
            }
        };
        connectivity.registerBestMatchingNetworkCallback(
                TailnetNode.PHYSICAL_NETWORK, callback, new Handler(Looper.getMainLooper()));
        try {
            String wifi = nextName(names);
            // Before a node starts, netmon must have no default route.
            assertEquals("ok", Probe.call("netchangeStart"));
            try (TailnetNode node = TailnetNode.start(
                    CONTEXT,
                    new MemoryStore(),
                    "klethron-netchange",
                    "http://127.0.0.1:1")) { // Unreachable: the node only follows the default network.
                assertEquals("ok", Probe.call("netchangeAwait", wifi));
                shell("svc wifi disable");
                String other = wifi;
                while (other.isEmpty() || other.equals(wifi)) {
                    other = nextName(names);
                }
                assertEquals("ok", Probe.call("netchangeAwait", other));
            }
        } finally {
            // Restore Wi-Fi and release the probe lock before cleanup can throw.
            Probe.call("netchangeStop");
            shell("svc wifi enable");
            connectivity.unregisterNetworkCallback(callback);
            boolean unused = awaitWifiDefault(connectivity); // Best effort; preserve the test failure.
        }
    }

    private static boolean awaitWifiDefault(ConnectivityManager connectivity) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static String nextName(LinkedBlockingQueue<String> names) throws InterruptedException {
        String name = names.poll(60, TimeUnit.SECONDS);
        assertNotNull("No default network report", name);
        return name;
    }
}
