package com.klethron.tsnet.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;
import com.klethron.tsnet.TailnetNode;
import com.klethron.tsnet.NodeStatus;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/// Interactive real-tailnet test. No URLs, credentials or peer data go to logs.
public final class LiveActivity extends Activity {
    // Serializes node calls across instances, including a finished instance's close.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private volatile boolean destroyed;
    // Accessed only on WORKER; lint checks thread annotations on methods, not fields.
    private @Nullable TailnetNode node;
    private @Nullable ParcelFileDescriptor held; // Kept-open connection.
    private @Nullable String heldTarget;
    // Accessed only on the UI thread.
    private @Nullable NodeStatus status; // Never saved in a Bundle.
    private boolean busy;
    private boolean started;

    @MainThread
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
        findViewById(R.id.pick_peer).setOnClickListener(view -> pickPeer());
        findViewById(R.id.exchange).setOnClickListener(view -> exchange());
        ((CheckBox) findViewById(R.id.keep_open)).setOnCheckedChangeListener((view, checked) -> {
            if (!checked) WORKER.execute(this::closeHeld);
        });
    }

    @MainThread
    private void startNode() {
        busy = true;
        render();
        WORKER.execute(() -> {
            if (destroyed) return;
            try {
                TailnetNode current = TailnetNode.start(this, new File(getNoBackupFilesDir(), "tailnet"), "klethron-tsnet-demo");
                node = current;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    started = true;
                    busy = false;
                    render();
                });
                new Thread(() -> watch(current), "tsnet-demo-status").start();
            } catch (IOException | RuntimeException | LinkageError failure) {
                complete(getString(R.string.operation_failed, failure.getClass().getSimpleName()));
            }
        });
    }

    @WorkerThread
    private void watch(TailnetNode current) {
        NodeStatus previous = null;
        try {
            while (!destroyed) {
                NodeStatus next = current.awaitStatus(previous, Duration.ofDays(1));
                runOnUiThread(() -> {
                    if (destroyed) return;
                    status = next;
                    render();
                });
                previous = next;
            }
        } catch (ClosedChannelException closed) {
            // closeNode closes it and wakes this waiter.
        }
    }

    @MainThread
    private void pickPeer() {
        if (status == null) return;
        List<NodeStatus.Peer> peers = status.peers();
        String[] labels = new String[peers.size()];
        for (int i = 0; i < labels.length; i++) {
            NodeStatus.Peer peer = peers.get(i);
            labels[i] = getString(peer.online() ? R.string.peer_online : R.string.peer_offline, peer.dnsName(), peer.os())
                    + (peer.sshHostKeys().isEmpty() ? "" : getString(R.string.peer_tailscale_ssh));
        }
        new AlertDialog.Builder(this).setTitle(R.string.pick_peer).setItems(labels, (dialog, which) ->
                ((EditText) findViewById(R.id.peer_host)).setText(peers.get(which).dnsName())).show();
    }

    @MainThread
    private void exchange() {
        String host = ((EditText) findViewById(R.id.peer_host)).getText().toString().trim();
        String portText = ((EditText) findViewById(R.id.peer_port)).getText().toString();
        boolean keepOpen = ((CheckBox) findViewById(R.id.keep_open)).isChecked();
        runOperation(current -> {
            int port = Integer.parseInt(portText); // dial validates the host and port range.
            byte[] sent = ("klethron-tsnet:" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII);
            long start = SystemClock.elapsedRealtime();
            // With Keep open, echoes reuse one stream, e.g. to test that it survives a network change.
            String target = host + ":" + port;
            if (!target.equals(heldTarget)) closeHeld();
            boolean opened = held == null;
            try {
                if (held == null) {
                    held = current.dial(host, port, TIMEOUT);
                    heldTarget = target;
                }
                echo(held, sent);
            } catch (Exception failure) {
                closeHeld();
                throw failure;
            }
            long elapsed = SystemClock.elapsedRealtime() - start;
            if (!keepOpen) closeHeld();
            return getString(opened ? R.string.echo_verified : R.string.echo_verified_open, sent.length, elapsed);
        });
    }

    /// Sends `sent` and requires it back, with each write and read limited to TIMEOUT.
    @WorkerThread
    private static void echo(ParcelFileDescriptor connection, byte[] sent) throws Exception {
        FileDescriptor fd = connection.getFileDescriptor();
        StructTimeval timeout = StructTimeval.fromMillis(TIMEOUT.toMillis());
        Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, timeout);
        Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, timeout);
        for (int offset = 0; offset < sent.length;) {
            offset += Os.write(fd, sent, offset, sent.length - offset);
        }
        byte[] received = new byte[sent.length];
        for (int offset = 0; offset < received.length;) {
            int count = Os.read(fd, received, offset, received.length - offset);
            if (count == 0) throw new IOException("Peer closed before echo");
            offset += count;
        }
        if (!Arrays.equals(sent, received)) throw new IOException("Echo mismatch");
    }

    @WorkerThread
    private void closeHeld() {
        heldTarget = null;
        if (held == null) return;
        try {
            held.close();
        } catch (IOException ignored) {
            // Closing a socket-pair end releases it even when close reports an error.
        }
        held = null;
    }

    private interface Operation {
        @WorkerThread
        String run(TailnetNode current) throws Exception;
    }

    @MainThread
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

    @AnyThread
    private void complete(String message) {
        runOnUiThread(() -> {
            if (destroyed) return;
            busy = false;
            result(message);
            render();
        });
    }

    @MainThread
    private void render() {
        findViewById(R.id.start_node).setEnabled(!busy && !started);
        findViewById(R.id.login).setEnabled(!busy && started);
        findViewById(R.id.logout).setEnabled(!busy && started);
        findViewById(R.id.authorize).setEnabled(status != null && status.loginUrl() != null);
        findViewById(R.id.pick_peer).setEnabled(status != null && !status.peers().isEmpty());
        // Also enabled when not RUNNING, to verify that access ends, e.g. after logout.
        findViewById(R.id.exchange).setEnabled(!busy && started);
        if (status != null) {
            String text = status.state().name();
            if (status.revocation() != null) text += " (" + status.revocation().name() + ")";
            if (status.error() != null) text += "\n" + getString(R.string.backend_error, status.error());
            if (status.loginUrl() != null) text += "\n" + getString(R.string.login_pending);
            else if (status.state() == NodeStatus.State.RUNNING) text += "\n" + getString(R.string.login_saved);
            ((TextView) findViewById(R.id.node_status)).setText(text);
        }
    }

    @MainThread
    private void result(String message) {
        ((TextView) findViewById(R.id.result)).setText(message);
    }

    @MainThread
    @Override
    public void onPause() {
        super.onPause();
        // Destruction can follow a new instance's start, which would find the state directory in use.
        if (isFinishing()) closeNode();
    }

    @MainThread
    @Override
    public void onDestroy() {
        closeNode();
        super.onDestroy();
    }

    @MainThread
    private void closeNode() {
        if (destroyed) return;
        destroyed = true;
        WORKER.execute(() -> {
            closeHeld();
            if (node != null) node.close();
            node = null;
        });
    }
}
