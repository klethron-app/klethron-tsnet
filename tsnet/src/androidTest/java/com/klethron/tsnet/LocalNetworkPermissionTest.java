package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.os.Build;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/// Socket-level error injection through the actual peer send paths, JNI and main-thread delivery.
@SuppressWarnings("LockOnNonEnclosingClassLiteral") // Shares the production network registration lock.
public final class LocalNetworkPermissionTest {
    private static final String PEER = "100.64.0.2";
    private static final String LAN = "192.0.2.9";
    private final AtomicInteger offers = new AtomicInteger();
    private volatile boolean granted;
    private volatile int targetSdk = 37;
    private TailnetNode node;
    private long handle;
    private Field networkField;
    private @Nullable Object realMonitor;

    @Before
    public void start() throws Exception {
        Context context = new ContextWrapper(CONTEXT) {
            @Override public Context getApplicationContext() { return this; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
                info.targetSdkVersion = targetSdk;
                return info;
            }
            @Override public int checkSelfPermission(String permission) {
                return granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
            }
        };
        node = TailnetNode.start(context, new MemoryStore(), "permission", "http://127.0.0.1:1");
        handle = Probe.handle(node);
        // Ignore real reports while supplying synthetic LAN prefixes. Otherwise initial Android
        // network delivery can correctly invalidate an offer in the middle of these fixture tests.
        networkField = TailnetNode.class.getDeclaredField("network");
        networkField.setAccessible(true);
        synchronized (TailnetNode.class) {
            realMonitor = networkField.get(null);
            networkField.set(null, null);
        }
    }

    @After
    public void close() {
        restoreNetwork();
        node.close();
    }

    @Test(timeout = 30000)
    public void directDataAndDiscoveryDenialsOfferOnMainThread() {
        for (String mode : List.of("data", "disco", "access")) {
            main(() -> {
                register(PEER);
                network("192.0.2.0/24");
                attempt(mode, PEER, LAN);
            });
            drain();
        }
        assertEquals(supported() ? 3 : 0, offers.get());
    }

    @Test(timeout = 30000)
    public void ignoresSuccessOtherErrorsStunAndRelaySends() {
        for (String mode : List.of("success", "unreachable", "stun", "relay")) {
            main(() -> {
                register(PEER);
                network("192.0.2.0/24");
                attempt(mode, PEER, LAN);
            });
            drain();
        }
        assertEquals(0, offers.get());
    }

    @Test(timeout = 30000)
    public void ignoresOtherPeersAndOffLinkDestinations() {
        main(() -> {
            register(PEER);
            network("192.0.2.0/24");
            attempt("data", "100.64.0.3", LAN);
            attempt("data", PEER, "192.168.20.1"); // Private does not mean on this LAN.
            attempt("data", PEER, "203.0.113.1");
            attempt("data", PEER, "127.0.0.1");
            attempt("data", PEER, "224.0.0.1");
        });
        drain();
        assertEquals(0, offers.get());
    }

    @Test(timeout = 30000)
    public void ipv6AndMappedIpv4UseCurrentPrefixes() {
        main(() -> {
            register("fd7a:115c:a1e0::2");
            network("2001:db8:1::/64");
            attempt("data", "fd7a:115c:a1e0::2", "2001:db8:2::9");
            attempt("data", "fd7a:115c:a1e0::2", "2001:db8:1::9");
        });
        drain();
        assertEquals(supported() ? 1 : 0, offers.get());
        main(() -> {
            register(PEER);
            network("192.0.2.0/24");
            attempt("data", PEER, "::ffff:192.0.2.9");
        });
        drain();
        assertEquals(supported() ? 2 : 0, offers.get());
    }

    @Test(timeout = 30000)
    public void offersOnceAndCanBeExplicitlyRearmed() {
        for (int i = 0; i < 2; i++) {
            main(() -> {
                register(PEER);
                network("192.0.2.0/24");
                attempt("data", PEER, LAN);
                attempt("disco", PEER, LAN);
            });
            drain();
        }
        assertEquals(supported() ? 2 : 0, offers.get());
    }

    @Test(timeout = 30000)
    public void hiddenDismissedAndReplacedOffersCancelQueuedDelivery() {
        main(() -> {
            register(PEER);
            network("192.0.2.0/24");
            attempt("data", PEER, LAN);
            node.setLocalNetworkPermissionCallback(null, null); // Hidden or dismissed.
            attempt("data", PEER, LAN);
        });
        drain();
        main(() -> {
            register(PEER);
            network("192.0.2.0/24");
            attempt("data", PEER, LAN);
            register("100.64.0.3");
        });
        drain();
        assertEquals(0, offers.get());
    }

    @Test(timeout = 30000)
    public void networkChangesAndLossCancelQueuedDelivery() {
        main(() -> {
            register(PEER);
            network("192.0.2.0/24");
            attempt("data", PEER, LAN);
            network("192.0.2.0/24"); // Replacement may reuse all addresses.
        });
        drain();
        main(() -> {
            attempt("data", PEER, LAN);
            LocalNetworkPermission.report(List.of());
            attempt("data", PEER, LAN);
        });
        drain();
        assertEquals(0, offers.get());
    }

    @Test(timeout = 30000)
    public void checksPermissionAndTargetSdkAtDelivery() {
        main(() -> {
            register(PEER);
            network("192.0.2.0/24");
            attempt("data", PEER, LAN);
            granted = true;
        });
        drain();
        main(() -> {
            granted = false;
            targetSdk = 36;
            attempt("data", PEER, LAN);
        });
        drain();
        assertEquals(0, offers.get());
    }

    @Test(timeout = 30000)
    public void closeCancelsQueuedDeliveryAndRegistrationRequiresMainThread() {
        assertThrows(IllegalStateException.class, () -> node.setLocalNetworkPermissionCallback(null, null));
        main(() -> {
            register(PEER);
            network("192.0.2.0/24");
            attempt("data", PEER, LAN);
            restoreNetwork();
            node.close();
            assertThrows(IllegalStateException.class, () -> node.setLocalNetworkPermissionCallback(null, null));
        });
        drain();
        assertEquals(0, offers.get());
    }

    private void register(String address) {
        node.setLocalNetworkPermissionCallback(new NodeStatus.Peer("peer", "linux", true,
                List.of(InetAddresses.parseNumericAddress(address)), List.of()), () -> {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            offers.incrementAndGet();
        });
    }

    private void attempt(String mode, String peer, String destination) {
        assertEquals("ok", Probe.call("peerUDPDenied", handle + "|" + mode + "|" + peer + "|" + destination));
    }

    private void restoreNetwork() {
        synchronized (TailnetNode.class) {
            if (realMonitor == null) return;
            try {
                networkField.set(null, realMonitor);
                realMonitor = null;
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static void network(String prefix) {
        String[] parts = prefix.split("/", -1);
        LocalNetworkPermission.report(List.of(
                new IpPrefix(InetAddresses.parseNumericAddress(parts[0]), Integer.parseInt(parts[1])),
                new IpPrefix(InetAddresses.parseNumericAddress("0.0.0.0"), 0)));
    }

    private static boolean supported() { return Build.VERSION.SDK_INT >= 37; }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void drain() { main(() -> {}); }
}
