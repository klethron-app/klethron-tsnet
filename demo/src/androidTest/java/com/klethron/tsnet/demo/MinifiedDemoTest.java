package com.klethron.tsnet.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public final class MinifiedDemoTest {
    @Test
    public void startsAndRestartsLiveNodeThroughMinifiedConsumer() {
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
            } finally {
                instrumentation.runOnMainSync(activity::finish);
                instrumentation.waitForIdleSync();
            }
        }
    }

    @Test
    public void loadsNativeLibraryAndClosesNodesThroughMinifiedConsumer() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setClassName(instrumentation.getTargetContext(), "com.klethron.tsnet.demo.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Exercise the real consumer without referencing TsnetNode or adding test keep rules.
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            Button button = activity.findViewById(instrumentation.getTargetContext().getResources()
                    .getIdentifier("smoke_test", "id", instrumentation.getTargetContext().getPackageName()));
            for (int i = 0; i < 2; i++) {
                instrumentation.runOnMainSync(button::performClick);
                // The node is created on a background thread; the button is re-enabled with the result.
                @Nullable String[] result = new String[1];
                for (long deadline = SystemClock.uptimeMillis() + 10000; result[0] == null; SystemClock.sleep(20)) {
                    assertTrue("No result from the demo", SystemClock.uptimeMillis() < deadline);
                    instrumentation.runOnMainSync(() -> result[0] = button.isEnabled() ? button.getText().toString() : null);
                }
                assertEquals("Loaded v1.105.0-pre.0.20261107202901-503010831ecd\n"
                                + "Node created and closed. No network started.",
                        result[0]);
            }
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
