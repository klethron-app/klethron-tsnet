package com.klethron.tsnet.demo;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public final class MinifiedDemoTest {
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
