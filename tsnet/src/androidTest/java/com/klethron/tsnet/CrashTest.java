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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * How a fatal Go failure in a consumer's process is recorded. Android starts apps with AT_SECURE,
 * so Go runs in secure mode with GOTRACEBACK=none: logcat gets the failure's first lines but never
 * a goroutine traceback, which would print raw argument words.
 */
public final class CrashTest {
    @Test
    public void panicAbortsWithItsMessageOnly() throws Exception {
        ApplicationExitInfo exit = crash(0);
        assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, exit.getReason());
        assertEquals(List.of("panic: klethron crash probe"), goLog(exit));
    }

    @Test
    public void nilDereferenceAbortsWithItsMessageOnly() throws Exception {
        ApplicationExitInfo exit = crash(1);
        assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, exit.getReason());
        List<String> log = goLog(exit);
        assertEquals(log.toString(), 2, log.size());
        assertEquals("panic: runtime error: invalid memory address or nil pointer dereference", log.get(0));
        assertTrue(log.get(1), log.get(1).startsWith("[signal SIGSEGV: segmentation violation code=0x1 addr=0x0 pc="));
    }

    @Test
    public void fatalErrorExitsWithoutTombstone() throws Exception {
        ApplicationExitInfo exit = crash(2);
        assertEquals(ApplicationExitInfo.REASON_EXIT_SELF, exit.getReason());
        assertEquals(2, exit.getStatus());
        assertEquals(List.of("fatal error: sync: unlock of unlocked mutex"), goLog(exit));
    }

    private static ApplicationExitInfo crash(int mode) throws Exception {
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
        try {
            IBinder binder = service.get(10, TimeUnit.SECONDS);
            // Match this process's exit: the system restarts the bound service after a crash, and
            // that process's later exit, or an earlier test's crash, must not be mistaken for it.
            ActivityManager activities = context.getSystemService(ActivityManager.class);
            int pid = Objects.requireNonNull(activities.getRunningAppProcesses()).stream()
                    .filter(process -> process.processName.equals(context.getPackageName() + ":crash"))
                    .findFirst().orElseThrow().pid;
            Parcel data = Parcel.obtain();
            try {
                binder.transact(IBinder.FIRST_CALL_TRANSACTION + mode, data, null, IBinder.FLAG_ONEWAY);
            } finally {
                data.recycle();
            }
            // The record can appear before the zygote reports how the process ended.
            for (int i = 0; i < 100; i++) {
                List<ApplicationExitInfo> exits = activities.getHistoricalProcessExitReasons(null, pid, 1);
                if (!exits.isEmpty() && exits.get(0).getReason() != ApplicationExitInfo.REASON_UNKNOWN) {
                    return exits.get(0);
                }
                Thread.sleep(100);
            }
        } finally {
            context.unbindService(connection);
        }
        throw new AssertionError("The crash process recorded no exit reason");
    }

    /** The crashed process's lines with Go's logcat tag. */
    private static List<String> goLog(ApplicationExitInfo exit) throws Exception {
        return NetworkChangeTest.shell("logcat -d -b main -v raw -s Go --pid=" + exit.getPid()).lines()
                .filter(line -> !line.startsWith("--------- beginning of ")).toList();
    }
}
