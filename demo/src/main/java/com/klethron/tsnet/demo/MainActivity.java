package com.klethron.tsnet.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import com.klethron.tsnet.TsnetNode;

public final class MainActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Button button = new Button(this);
        button.setText(R.string.load_native);
        button.setOnClickListener(view -> {
            try (TsnetNode ignored = new TsnetNode()) {
                button.setText(getString(R.string.native_loaded, TsnetNode.tailscaleVersion()));
            } catch (LinkageError | RuntimeException failure) {
                button.setText(getString(R.string.native_failed, failure.getClass().getSimpleName()));
            }
        });
        setContentView(button);
    }
}
