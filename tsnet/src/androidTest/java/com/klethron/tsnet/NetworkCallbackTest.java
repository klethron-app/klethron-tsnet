package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static org.junit.Assert.assertEquals;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Objects;
import org.junit.Test;

public final class NetworkCallbackTest {
    @Test(timeout = 30000)
    @SuppressWarnings("NullAway") // The callback uses link properties, not the Network argument.
    public void ignoresCallbacksFromAClosedRegistration() throws Exception {
        ConnectivityManager.NetworkCallback previous;
        try (TailnetNode node = start()) {
            previous = currentCallback();
        }
        LinkProperties properties = new LinkProperties();
        properties.setInterfaceName("lo");
        previous.onLinkPropertiesChanged(null, properties);
        assertEquals("", Probe.call("defaultRoute"));

        try (TailnetNode node = start()) {
            ConnectivityManager.NetworkCallback current = currentCallback();
            // No real network callback can interleave with these simulated deliveries.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                current.onLinkPropertiesChanged(null, properties);
                assertEquals("lo", Probe.call("defaultRoute"));
                previous.onLost(null);
                assertEquals("lo", Probe.call("defaultRoute"));
                properties.setInterfaceName("stale");
                previous.onLinkPropertiesChanged(null, properties);
                assertEquals("lo", Probe.call("defaultRoute"));
                current.onLost(null);
                assertEquals("", Probe.call("defaultRoute"));
            });
        }
        assertEquals("", Probe.call("defaultRoute"));
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
