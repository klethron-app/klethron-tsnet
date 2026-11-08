package com.klethron.tsnet.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import com.klethron.tsnet.TsnetNode;
import org.jspecify.annotations.Nullable;

public final class MainActivity extends Activity {
    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Button button = new Button(this);
        button.setText(R.string.load_native);
        button.setOnClickListener(view -> {
            button.setEnabled(false);
            // Node creation reads the CA store on first use, so keep it off the UI thread.
            new Thread(() -> {
                String result;
                try (TsnetNode ignored = new TsnetNode()) {
                    result = getString(R.string.native_loaded, TsnetNode.tailscaleVersion());
                } catch (LinkageError | RuntimeException failure) {
                    result = getString(R.string.native_failed, failure.getClass().getSimpleName());
                }
                String text = result;
                button.post(() -> {
                    button.setText(text);
                    button.setEnabled(true);
                });
            }, "tsnet-demo").start();
        });
        setContentView(button);
    }
}
