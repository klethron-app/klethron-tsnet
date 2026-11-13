package com.klethron.tsnet.demo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import org.junit.Test;

public final class MinifiedDemoTest {
    @Test(timeout = 20000)
    public void tricklingEchoCannotExtendTheDeadline() throws Exception {
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        var worker = Executors.newSingleThreadExecutor();
        try (ParcelFileDescriptor client = pair[0]; ParcelFileDescriptor peer = pair[1]) {
            byte[] sent = {1, 2, 3, 4, 5, 6};
            var trickle = worker.submit(() -> {
                for (byte value : sent) {
                    Thread.sleep(2000);
                    Os.write(peer.getFileDescriptor(), new byte[] {value}, 0, 1);
                }
                return null;
            });
            Exception failure = assertThrows(Exception.class, () -> LiveActivity.echo(client, sent));
            assertTrue(failure instanceof SocketTimeoutException
                    || (failure instanceof ErrnoException errno && errno.errno == OsConstants.EAGAIN));
            assertFalse("Peer stopped before the deadline", trickle.isDone());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void startsRotatesAndRestartsLiveNodeThroughMinifiedConsumer() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        Intent intent = new Intent().setClassName(context, "com.klethron.tsnet.demo.LiveActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (int i = 0; i < 2; i++) {
            Activity activity = instrumentation.startActivitySync(intent);
            try {
                int start = context.getResources().getIdentifier("start_node", "id", context.getPackageName());
                int status = context.getResources().getIdentifier("node_status", "id", context.getPackageName());
                int authorize = context.getResources().getIdentifier("authorize", "id", context.getPackageName());
                instrumentation.runOnMainSync(() -> activity.findViewById(start).performClick());
                boolean[] ready = {false};
                for (long deadline = SystemClock.uptimeMillis() + 45000; !ready[0]; SystemClock.sleep(50)) {
                    assertTrue("No control-plane status from live demo", SystemClock.uptimeMillis() < deadline);
                    instrumentation.runOnMainSync(() -> {
                        String text = ((TextView) activity.findViewById(status)).getText().toString();
                        // Never open or inspect the authorization URL, or change an existing login.
                        ready[0] = activity.findViewById(authorize).isEnabled()
                                || text.startsWith("RUNNING") || text.startsWith("NEEDS_MACHINE_AUTH");
                    });
                }
                if (i == 0) {
                    // A configuration change must keep the screen, and with it the node and a pending login.
                    instrumentation.runOnMainSync(() ->
                            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                    for (long deadline = SystemClock.uptimeMillis() + 10000; context.getResources().getConfiguration()
                            .orientation != Configuration.ORIENTATION_LANDSCAPE; SystemClock.sleep(50)) {
                        assertTrue("Live demo did not rotate", SystemClock.uptimeMillis() < deadline);
                    }
                    instrumentation.waitForIdleSync();
                    assertTrue("Rotation recreated the live demo", !activity.isDestroyed());
                }
            } finally {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        }
    }
}
