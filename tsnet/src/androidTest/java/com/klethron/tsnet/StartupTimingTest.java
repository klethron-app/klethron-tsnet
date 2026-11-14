package com.klethron.tsnet;

import static com.klethron.tsnet.TestSupport.CONTEXT;
import static com.klethron.tsnet.TestSupport.WAIT;
import static com.klethron.tsnet.TestSupport.await;
import static com.klethron.tsnet.TestSupport.deleteDirectory;
import static com.klethron.tsnet.TestSupport.temporaryDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import com.klethron.tsnet.demo.store.SqliteStore;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

/// Opt-in phase timings against loopback control. Run alone with runner argument `startupTiming=true`.
public final class StartupTimingTest {
    private static final String TAG = "TsnetStartupTiming";
    private static final String PEER = "klethron-peer.tail-scale.ts.net";

    @Test(timeout = 180000)
    public void persistedRestartsAndWarmConnections() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("startupTiming")));
        long initialized = System.nanoTime();
        assertTrue(!TailnetNode.tailscaleVersion().isEmpty());
        report(-1, "class_init", System.nanoTime() - initialized);
        String control = Probe.call("startControl", false);
        assertTrue(control.startsWith("http://127.0.0.1:"));
        Path directory = temporaryDirectory("startup-timing-");
        try (SqliteStore store = new SqliteStore(directory.resolve("state.db").toFile())) {
            assertTrue(Probe.call("startPeer").startsWith("100."));
            List<InetAddress> addresses = List.of();
            for (int iteration = 0; iteration < 10; iteration++) {
                long started = System.nanoTime();
                TailnetNode node = TailnetNode.start(CONTEXT, store, "klethron-timing", control);
                long localReady = System.nanoTime();
                long running, connected, echoed, warmStarted, warmConnected, closing, closed;
                try {
                    NodeStatus status = await(node, current -> current.state() == NodeStatus.State.RUNNING
                            && current.loginUrl() == null
                            && current.peers().stream().anyMatch(peer -> PEER.equals(peer.dnsName())));
                    running = System.nanoTime();
                    if (iteration == 0) addresses = status.addresses();
                    else assertEquals("Restart must reuse the saved identity", addresses, status.addresses());
                    try (SocketChannel channel = node.connect(PEER, 7070, WAIT)) {
                        connected = System.nanoTime();
                        echo(channel);
                        echoed = System.nanoTime();
                    }
                    warmStarted = System.nanoTime();
                    try (SocketChannel channel = node.connect(PEER, 7070, WAIT)) {
                        warmConnected = System.nanoTime();
                        echo(channel);
                    }
                } finally {
                    closing = System.nanoTime();
                    node.close();
                    closed = System.nanoTime();
                }
                report(iteration, "local_start", localReady - started);
                report(iteration, "running_map", running - localReady);
                report(iteration, "first_connect", connected - running);
                report(iteration, "first_echo", echoed - connected);
                report(iteration, "start_to_echo", echoed - started);
                report(iteration, "warm_connect", warmConnected - warmStarted);
                report(iteration, "close", closed - closing);
            }
        } finally {
            Probe.call("stopControl");
            deleteDirectory(directory);
        }
    }

    private static void echo(SocketChannel channel) throws Exception {
        channel.socket().setSoTimeout(30000);
        assertEquals(1, channel.write(ByteBuffer.wrap(new byte[] {1})));
        assertEquals(1, channel.socket().getInputStream().read());
    }

    private static void report(int iteration, String phase, long elapsed) {
        Log.i(TAG, String.format(Locale.ROOT, "iteration=%d phase=%s elapsed_ms=%.3f",
                iteration, phase, elapsed / 1_000_000.0));
    }
}
