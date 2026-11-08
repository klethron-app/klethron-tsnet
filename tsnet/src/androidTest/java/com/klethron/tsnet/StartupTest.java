package com.klethron.tsnet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.Test;

public final class StartupTest {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    @Test(timeout = 60000)
    public void startsWithoutWritingFilesAndClosesRepeatedly() throws Exception {
        assertTrue("Startup must run off the UI thread", Looper.myLooper() != Looper.getMainLooper());
        // A supplementary character catches accidental JNI modified-UTF-8 conversion.
        Path directory = temporaryDirectory("startup-\uD83D\uDD12-");
        try {
            for (int i = 0; i < 3; i++) {
                assertEquals("ok", probe(directory));
                try (var files = Files.list(directory)) {
                    assertEquals("Startup must not write log configuration, buffers or node state", 0, files.count());
                }
            }
        } finally {
            deleteDirectory(directory);
        }
    }

    @Test(timeout = 30000)
    public void closesAfterStartupFailure() throws Exception {
        Path directory = temporaryDirectory("startup-failure-");
        try {
            Path file = Files.createFile(directory.resolve("not-a-directory"));
            assertEquals("start failed", probe(file));
            assertEquals(0, Files.size(file));
            try (var files = Files.list(directory)) {
                assertEquals(1, files.count());
            }
        } finally {
            deleteDirectory(directory);
        }
    }

    private static Path temporaryDirectory(String prefix) throws Exception {
        return Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getNoBackupFilesDir().toPath(), prefix);
    }

    private static String probe(Path directory) {
        return nativeProbe(directory.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteDirectory(Path directory) throws Exception {
        // Delete only this test's private temporary directory; never read or report its contents.
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static native String nativeProbe(byte[] directory);
}
