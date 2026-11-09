package com.klethron.tsnet.demo;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import android.widget.EditText;
import android.widget.TextView;
import com.klethron.tsnet.TsnetNode;
import com.klethron.tsnet.TsnetStatus;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/** Explicit, interactive real-tailnet test. No URLs, credentials or peer data go to logs. */
public final class LiveActivity extends Activity {
    // Serializes state-directory ownership even across activity recreation. Close precedes restart.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private @Nullable TsnetNode node; // Only accessed on WORKER.
    private @Nullable TsnetStatus status; // Only accessed on the UI thread; never saved in a Bundle.
    private boolean busy;
    private boolean started;
    private boolean registered;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            if (destroyed) return;
            Inet4Address gateway = null;
            for (var route : properties.getRoutes()) {
                if (route.isDefaultRoute() && route.hasGateway() && route.getGateway() instanceof Inet4Address address) {
                    gateway = address;
                    break;
                }
            }
            TsnetNode.setDefaultNetwork(properties.getInterfaceName(), gateway);
        }

        @Override
        public void onLost(Network network) {
            if (!destroyed) TsnetNode.setDefaultNetwork(null, null);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedState) {
        super.onCreate(savedState);
        setContentView(R.layout.live);
        findViewById(R.id.start_node).setOnClickListener(view -> startNode());
        findViewById(R.id.authorize).setOnClickListener(view -> {
            String url = status == null ? null : status.loginUrl();
            if (url == null) return;
            try {
                // An explicit tap sends the URL only to a browser, never to a text view or clipboard.
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browser.setSelector(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
                startActivity(browser);
            } catch (ActivityNotFoundException failure) {
                result(getString(R.string.browser_missing));
            }
        });
        findViewById(R.id.login).setOnClickListener(view -> runOperation(current -> {
            current.login();
            return getString(R.string.login_requested);
        }));
        findViewById(R.id.logout).setOnClickListener(view -> runOperation(current -> {
            current.logout(TIMEOUT);
            return getString(R.string.logged_out);
        }));
        findViewById(R.id.exchange).setOnClickListener(view -> exchange());
        findViewById(R.id.close_node).setOnClickListener(view -> finish());
    }

    private void startNode() {
        busy = true;
        render();
        WORKER.execute(() -> {
            if (destroyed) return;
            try {
                TsnetNode current = new TsnetNode();
                node = current;
                current.start(new File(getNoBackupFilesDir(), "tailnet"), "klethron-tsnet-demo");
                ui.post(() -> {
                    if (destroyed) return;
                    getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback, ui);
                    registered = true;
                    started = true;
                    busy = false;
                    render();
                });
                new Thread(() -> watch(current), "tsnet-demo-status").start();
            } catch (IOException | RuntimeException | LinkageError failure) {
                if (node != null) node.close();
                node = null;
                complete(getString(R.string.operation_failed, failure.getClass().getSimpleName()));
            }
        });
    }

    private void watch(TsnetNode current) {
        TsnetStatus previous = null;
        try {
            while (!destroyed) {
                TsnetStatus next = current.awaitStatus(previous, Duration.ofSeconds(1));
                ui.post(() -> {
                    if (destroyed) return;
                    status = next;
                    render();
                });
                previous = next;
            }
        } catch (IllegalStateException closed) {
            // onDestroy closes the node and wakes this waiter.
        }
    }

    private void exchange() {
        String host = ((EditText) findViewById(R.id.peer_host)).getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(((EditText) findViewById(R.id.peer_port)).getText().toString());
            if (host.isEmpty() || port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            result(getString(R.string.invalid_destination));
            return;
        }
        runOperation(current -> {
            byte[] sent = ("klethron-tsnet:" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII);
            byte[] received = new byte[sent.length];
            long start = SystemClock.elapsedRealtime();
            try (ParcelFileDescriptor connection = current.dial(host, port, TIMEOUT)) {
                long deadline = SystemClock.elapsedRealtime() + TIMEOUT.toMillis();
                for (int offset = 0; offset < sent.length;) {
                    socketTimeout(connection, OsConstants.SO_SNDTIMEO, deadline);
                    offset += Os.write(connection.getFileDescriptor(), sent, offset, sent.length - offset);
                }
                for (int offset = 0; offset < received.length;) {
                    socketTimeout(connection, OsConstants.SO_RCVTIMEO, deadline);
                    int count = Os.read(connection.getFileDescriptor(), received, offset, received.length - offset);
                    if (count == 0) throw new IOException("Peer closed before echo");
                    offset += count;
                }
                if (!Arrays.equals(sent, received)) throw new IOException("Echo mismatch");
            }
            return getString(R.string.echo_verified, sent.length, SystemClock.elapsedRealtime() - start);
        });
    }

    private static void socketTimeout(ParcelFileDescriptor connection, int option, long deadline)
            throws SocketTimeoutException, android.system.ErrnoException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new SocketTimeoutException();
        Os.setsockoptTimeval(connection.getFileDescriptor(), OsConstants.SOL_SOCKET, option,
                StructTimeval.fromMillis(remaining));
    }

    private interface Operation {
        String run(TsnetNode current) throws Exception;
    }

    private void runOperation(Operation operation) {
        busy = true;
        render();
        result(getString(R.string.working));
        WORKER.execute(() -> {
            if (destroyed || node == null) return;
            try {
                complete(operation.run(node));
            } catch (Exception failure) {
                // Exception messages may include peer details; show only the exception type.
                complete(getString(R.string.operation_failed, failure.getClass().getSimpleName()));
            }
        });
    }

    private void complete(String message) {
        ui.post(() -> {
            if (destroyed) return;
            busy = false;
            result(message);
            render();
        });
    }

    private void render() {
        findViewById(R.id.start_node).setEnabled(!busy && !started);
        findViewById(R.id.login).setEnabled(!busy && started);
        findViewById(R.id.logout).setEnabled(!busy && started);
        findViewById(R.id.authorize).setEnabled(status != null && status.loginUrl() != null);
        findViewById(R.id.exchange).setEnabled(!busy && status != null && status.state() == TsnetStatus.State.RUNNING);
        if (status != null) {
            String text = status.state().name();
            if (status.loginUrl() != null) text += "\n" + getString(R.string.login_pending);
            else if (status.state() == TsnetStatus.State.RUNNING) text += "\n" + getString(R.string.login_saved);
            ((TextView) findViewById(R.id.node_status)).setText(text);
        }
    }

    private void result(String message) {
        ((TextView) findViewById(R.id.result)).setText(message);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        status = null;
        if (registered) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            TsnetNode.setDefaultNetwork(null, null);
        }
        WORKER.execute(() -> {
            if (node != null) node.close();
            node = null;
        });
        super.onDestroy();
    }
}
