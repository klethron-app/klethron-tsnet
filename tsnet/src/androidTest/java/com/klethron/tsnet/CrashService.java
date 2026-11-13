package com.klethron.tsnet;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Crashes its own process in Go on a transaction, so the test process can observe the crash. */
public final class CrashService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        // Crash on a call, not in onBind, so the service restart after the crash does not repeat it.
        return new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, @Nullable Parcel reply, int flags) {
                if (code == LAST_CALL_TRANSACTION) {
                    Objects.requireNonNull(reply).writeString(Probe.call("holdDirectory", Objects.requireNonNull(data.readString())));
                } else {
                    Probe.call("crash", code - FIRST_CALL_TRANSACTION);
                }
                return true;
            }
        };
    }
}
