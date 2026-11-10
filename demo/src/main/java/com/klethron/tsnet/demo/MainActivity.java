package com.klethron.tsnet.demo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;
import com.klethron.tsnet.TailnetNode;
import org.jspecify.annotations.Nullable;

public final class MainActivity extends Activity {
    @MainThread
    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Button button = new Button(this);
        button.setId(R.id.smoke_test);
        button.setText(R.string.load_native);
        button.setOnClickListener(view -> {
            button.setEnabled(false);
            new Thread(() -> {
                String text = loadNative();
                button.post(() -> {
                    button.setText(text);
                    button.setEnabled(true);
                });
            }, "tsnet-demo").start();
        });
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(button);
        Button live = new Button(this);
        live.setText(R.string.live_test);
        live.setOnClickListener(view -> startActivity(new Intent(this, LiveActivity.class)));
        layout.addView(live);
        setContentView(layout);
    }

    @WorkerThread // Loading the native library reads a large file.
    private String loadNative() {
        try {
            return getString(R.string.native_loaded, TailnetNode.tailscaleVersion());
        } catch (LinkageError | RuntimeException failure) {
            return getString(R.string.native_failed, failure.getClass().getSimpleName());
        }
    }
}
