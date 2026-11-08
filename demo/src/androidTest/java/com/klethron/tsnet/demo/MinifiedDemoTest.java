package com.klethron.tsnet.demo;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public final class MinifiedDemoTest {
    @Test
    public void loadsNativeLibraryAndClosesNodesThroughMinifiedConsumer() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setClassName(instrumentation.getTargetContext(), "com.klethron.tsnet.demo.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Exercise the real consumer without referencing TsnetNode or adding test keep rules.
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            String[] result = new String[1];
            for (int i = 0; i < 2; i++) {
                instrumentation.runOnMainSync(() -> {
                    ViewGroup content = activity.findViewById(android.R.id.content);
                    Button button = (Button) content.getChildAt(0);
                    button.performClick();
                    result[0] = button.getText().toString();
                });
                assertEquals("Loaded v1.105.0-pre.0.20261107202901-503010831ecd\n"
                                + "Node created and closed. Networking is not implemented yet.",
                        result[0]);
            }
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
