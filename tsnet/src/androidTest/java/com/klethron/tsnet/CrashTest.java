package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import com.klethron.tsnet.demo.store.SqliteStore;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/// Fatal failures preserve caller-owned state and, under Android's AT_SECURE, omit Go argument dumps.
public final class CrashTest {
    @Test
    public void panicAbortsWithItsMessageOnly() throws Exception {
        ApplicationExitInfo exit = crash(0, ApplicationExitInfo.REASON_CRASH_NATIVE);
        assertEquals(List.of("panic: klethron crash probe"), goLog(exit));
    }

    @Test
    public void nilDereferenceAbortsWithItsMessageOnly() throws Exception {
        List<String> log = goLog(crash(1, ApplicationExitInfo.REASON_CRASH_NATIVE));
        assertEquals(log.toString(), 2, log.size());
        assertEquals("panic: runtime error: invalid memory address or nil pointer dereference", log.get(0));
        assertTrue(log.get(1), log.get(1).startsWith("[signal SIGSEGV: segmentation violation code=0x1 addr=0x0 pc="));
    }

    @Test
    public void fatalErrorExitsWithoutTombstone() throws Exception {
        ApplicationExitInfo exit = crash(2, ApplicationExitInfo.REASON_EXIT_SELF);
        assertEquals(2, exit.getStatus());
        assertEquals(List.of("fatal error: sync: unlock of unlocked mutex"), goLog(exit));
    }

    private static ApplicationExitInfo crash(int mode, int reason) throws Exception {
        Context context = LoginTest.CONTEXT;
        CompletableFuture<IBinder> service = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service.complete(binder);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {}
        };
        assertTrue(context.bindService(new Intent(context, CrashService.class), connection, Context.BIND_AUTO_CREATE));
        Path directory = LoginTest.temporaryDirectory("crash-");
        byte[] machineKey;
        try {
            IBinder binder = service.get(10, TimeUnit.SECONDS);
            // Match this process, not an earlier crash or the restarted service.
            ActivityManager activities = context.getSystemService(ActivityManager.class);
            int pid = Objects.requireNonNull(activities.getRunningAppProcesses()).stream()
                    .filter(process -> process.processName.equals(context.getPackageName() + ":crash"))
                    .findFirst().orElseThrow().pid;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeString(directory.resolve("state.db").toString());
                binder.transact(IBinder.LAST_CALL_TRANSACTION, data, reply, 0);
                assertEquals("ok", reply.readString());
                try (SqliteStore store = new SqliteStore(directory.resolve("state.db").toFile())) {
                    machineKey = Objects.requireNonNull(store.read("_machinekey"));
                }
                binder.transact(IBinder.FIRST_CALL_TRANSACTION + mode, data, null, IBinder.FLAG_ONEWAY);
            } finally {
                reply.recycle();
                data.recycle();
            }
            // The record reads REASON_UNKNOWN until the zygote reports how the process ended, and an
            // abort's REASON_SIGNALED until ActivityManager notes the crash report.
            List<ApplicationExitInfo> exits = List.of();
            for (int i = 0; i < 100; i++) {
                exits = activities.getHistoricalProcessExitReasons(null, pid, 1);
                if (!exits.isEmpty() && exits.get(0).getReason() == reason) {
                    try (SqliteStore store = new SqliteStore(directory.resolve("state.db").toFile());
                            TailnetNode node = TailnetNode.start(context, store, "recovered", "http://127.0.0.1:1")) {
                        assertTrue("Machine identity changed after crash", Arrays.equals(machineKey, store.read("_machinekey")));
                    }
                    return exits.get(0);
                }
                Thread.sleep(100);
            }
            throw new AssertionError("No exit reason " + reason + ": " + exits);
        } finally {
            context.unbindService(connection);
            LoginTest.deleteDirectory(directory);
        }
    }

    private static List<String> goLog(ApplicationExitInfo exit) throws Exception {
        return NetworkChangeTest.shell("logcat -d -b main -v raw -s Go --pid=" + exit.getPid()).lines()
                .filter(line -> !line.startsWith("--------- beginning of ")).toList();
    }
}
