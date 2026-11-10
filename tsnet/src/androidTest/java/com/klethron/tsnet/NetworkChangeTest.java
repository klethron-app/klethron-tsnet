package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Acts as the consuming app: forwards ConnectivityManager's default network to
 * {@link TailnetNode#setDefaultNetwork} while a loopback two-node tailnet runs, then disables Wi-Fi
 * so the default network really changes. Both nodes must rebind and keep exchanging data.
 * Requires a device whose default network is Wi-Fi with another network (emulator cellular) behind it.
 */
public final class NetworkChangeTest {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    @Test(timeout = 180000)
    public void rebindsWhenDefaultNetworkChanges() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        assertTrue("The default network must be Wi-Fi", awaitWifiDefault(connectivity));

        TailnetNode.setDefaultNetwork(null);
        Path directory = LoginTest.temporaryDirectory("netchange-");
        LinkedBlockingQueue<String> names = new LinkedBlockingQueue<>();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                String name = properties.getInterfaceName();
                TailnetNode.setDefaultNetwork(properties);
                names.add(name == null ? "" : name);
            }

            @Override
            public void onLost(Network network) {
                TailnetNode.setDefaultNetwork(null);
                names.add("");
            }
        };
        boolean registered = false;
        try {
            assertEquals("ok", nativeProbeStart(directory.toString().getBytes(StandardCharsets.UTF_8)));
            connectivity.registerBestMatchingNetworkCallback(TailnetNode.PHYSICAL_NETWORK, callback,
                    new Handler(Looper.getMainLooper()));
            registered = true;
            String wifi = nextName(names);
            assertEquals("ok", nativeProbeAwait(wifi.getBytes(StandardCharsets.UTF_8)));

            shell("svc wifi disable");
            String other = wifi;
            while (other.isEmpty() || other.equals(wifi)) {
                other = nextName(names);
            }
            assertEquals("ok", nativeProbeAwait(other.getBytes(StandardCharsets.UTF_8)));
            nativeProbeStop();
            try (var files = Files.list(directory)) {
                assertEquals("Nodes must not write node state", 0, files.count());
            }
        } finally {
            if (registered) {
                connectivity.unregisterNetworkCallback(callback);
            }
            // Release the probe first: it holds the lock that other tailnet probes wait for.
            nativeProbeStop();
            TailnetNode.setDefaultNetwork(null);
            LoginTest.deleteDirectory(directory);
            shell("svc wifi enable");
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

    private static native String nativeProbeStart(byte[] directory);

    private static native String nativeProbeAwait(byte[] interfaceName);

    private static native void nativeProbeStop();
}
