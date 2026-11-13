package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static org.junit.Assert.assertEquals;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.Test;

public final class NetworkCallbackTest {
    @Test(timeout = 30000)
    public void ignoresCallbacksFromAClosedRegistration() throws Exception {
        Network physical = Objects.requireNonNull(CONTEXT.getSystemService(ConnectivityManager.class).getActiveNetwork());
        ConnectivityManager.NetworkCallback previous;
        try (TailnetNode node = start()) {
            previous = currentCallback();
        }
        LinkProperties properties = new LinkProperties();
        properties.setInterfaceName("lo");
        previous.onLinkPropertiesChanged(physical, properties);
        assertEquals("", Probe.call("defaultRoute"));

        try (TailnetNode node = start()) {
            ConnectivityManager.NetworkCallback current = currentCallback();
            // No real network callback can interleave with these simulated deliveries.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                current.onLinkPropertiesChanged(physical, properties);
                assertEquals("lo", Probe.call("defaultRoute"));
                previous.onLost(physical);
                assertEquals("lo", Probe.call("defaultRoute"));
                properties.setInterfaceName("stale");
                previous.onLinkPropertiesChanged(physical, properties);
                assertEquals("lo", Probe.call("defaultRoute"));
                current.onLost(physical);
                assertEquals("", Probe.call("defaultRoute"));
            });
        }
        assertEquals("", Probe.call("defaultRoute"));
    }

    @Test(timeout = 90000)
    public void rebindsWhenAReplacementNetworkReusesTheInterface() throws Exception {
        Method report = TailnetNode.class.getDeclaredMethod("nativeSetDefaultNetwork", byte[].class, long.class);
        report.setAccessible(true);
        assertEquals("ok", Probe.call("netchangeStart"));
        try (TailnetNode node = start()) {
            // Keep real callbacks from interleaving with simulated Android network identities.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                reportNetwork(report, "lo", 1);
                assertEquals("ok", Probe.call("netchangeAwait", "lo"));
                assertEquals("ok", Probe.call("netchangeReset"));
                reportNetwork(report, "lo", 1);
                assertEquals("ok", Probe.call("netchangeUnchanged"));

                // No interface, address or gateway changes: only the Network handle differs.
                reportNetwork(report, "lo", 2);
                assertEquals("ok", Probe.call("netchangeAwait", "lo"));
                assertEquals("ok", Probe.call("netchangeReset"));
                reportNetwork(report, "", 0);
                reportNetwork(report, "lo", 3);
                assertEquals("ok", Probe.call("netchangeAwait", "lo"));
            });
        } finally {
            Probe.call("netchangeStop");
        }
    }

    private static void reportNetwork(Method report, String name, long handle) {
        try {
            report.invoke(null, (name + '\0').getBytes(StandardCharsets.UTF_8), handle);
        } catch (ReflectiveOperationException failure) {
            throw new LinkageError(failure.getMessage(), failure);
        }
    }

    private static TailnetNode start() throws Exception {
        return TailnetNode.start(CONTEXT, new MemoryStore(), "callback", "http://127.0.0.1:1");
    }

    private static ConnectivityManager.NetworkCallback currentCallback() throws Exception {
        var field = TailnetNode.class.getDeclaredField("network");
        field.setAccessible(true);
        return (ConnectivityManager.NetworkCallback) Objects.requireNonNull(field.get(null));
    }
}
