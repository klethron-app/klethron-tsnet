package com.klethron.tsnet;

import android.app.Service;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.util.Objects;
import java.io.File;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/** Crashes its own process in Go on a transaction, so the test process can observe the crash. */
public final class CrashService extends Service {
    private @Nullable TailnetNode node;
    private @Nullable SqliteStore store;

    @Override
    public IBinder onBind(Intent intent) {
        // Crash on a call, not in onBind, so the service restart after the crash does not repeat it.
        return new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, @Nullable Parcel reply, int flags) {
                if (code == LAST_CALL_TRANSACTION) {
                    try {
                        store = new SqliteStore(new File(Objects.requireNonNull(data.readString())));
                        node = TailnetNode.start(CrashService.this, store, "crash", "http://127.0.0.1:1");
                        Objects.requireNonNull(reply).writeString("ok");
                    } catch (IOException failure) {
                        Objects.requireNonNull(reply).writeString("failed");
                    }
                } else {
                    Probe.call("crash", code - FIRST_CALL_TRANSACTION);
                }
                return true;
            }
        };
    }

    @Override
    @SuppressLint("WrongThread")
    public void onDestroy() {
        if (node != null) node.close();
        if (store != null) store.close();
        super.onDestroy();
    }
}
