package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Runs a loopback two-node tailnet while a node started through the public API follows the
 * network, then disables Wi-Fi so the network really changes. Both tailnet nodes
 * must rebind and keep exchanging data. Requires Wi-Fi with cellular behind it; another app's VPN,
 * split or full tunnel, may be active.
 */
public final class NetworkChangeTest {
    @Test(timeout = 180000)
    public void rebindsWhenDefaultNetworkChanges() throws Exception {
        ConnectivityManager connectivity = LoginTest.CONTEXT.getSystemService(ConnectivityManager.class);
        assertTrue("The default network must be Wi-Fi", awaitWifiDefault(connectivity));

        Path directory = LoginTest.temporaryDirectory("netchange-");
        Path nodeDirectory = LoginTest.temporaryDirectory("netchange-node-");
        // Learns the interface names of the network the library follows; only it reports them to the nodes.
        LinkedBlockingQueue<String> names = new LinkedBlockingQueue<>();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                String name = properties.getInterfaceName();
                names.add(name == null ? "" : name);
            }
        };
        connectivity.registerBestMatchingNetworkCallback(TailnetNode.PHYSICAL_NETWORK, callback,
                new Handler(Looper.getMainLooper()));
        try {
            String wifi = nextName(names);
            // Checks that, with no node running, Android netmon has no default route.
            assertEquals("ok", Probe.call("netchangeStart", directory));
            try (TailnetNode node = TailnetNode.start(LoginTest.CONTEXT, nodeDirectory.toFile(), "klethron-netchange",
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
            // Release the probe and Wi-Fi before cleanup that can throw: other tailnet probes wait
            // for the probe's lock, and later tests need Wi-Fi.
            Probe.call("netchangeStop");
            shell("svc wifi enable");
            connectivity.unregisterNetworkCallback(callback);
            LoginTest.deleteDirectory(directory);
            LoginTest.deleteDirectory(nodeDirectory);
            // Leave the device as found; Wi-Fi reconnects asynchronously.
            boolean unused = awaitWifiDefault(connectivity); // Best effort: must not mask a test failure.
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

    private static void shell(String command) throws Exception {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (ParcelFileDescriptor output = automation.executeShellCommand(command);
                var in = new FileInputStream(output.getFileDescriptor())) {
            in.readAllBytes(); // Wait for completion.
        }
    }
}
