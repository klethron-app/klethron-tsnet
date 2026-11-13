package com.klethron.tsnet;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.function.Predicate;

final class TestSupport {
    static final Context CONTEXT = InstrumentationRegistry.getInstrumentation().getTargetContext();
    static final Duration WAIT = Duration.ofSeconds(30);

    @CanIgnoreReturnValue
    static NodeStatus await(TailnetNode node, Predicate<NodeStatus> condition) throws IOException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        NodeStatus status = null;
        do {
            status = node.awaitStatus(status, Duration.ofNanos(deadline - System.nanoTime()));
            if (condition.test(status)) return status;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out at " + status);
    }

    @CanIgnoreReturnValue
    static <T extends Throwable> T assertDialThrows(Class<T> expected, TailnetNode node, String host, int port, Duration timeout) {
        return assertThrows(expected, () -> node.connect(host, port, timeout).close());
    }

    static Path temporaryDirectory(String prefix) throws IOException {
        return Files.createTempDirectory(CONTEXT.getNoBackupFilesDir().toPath(), prefix);
    }

    static void deleteDirectory(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /// Returns the output of `command`, run as the shell user, once it completes.
    @CanIgnoreReturnValue
    static String shell(String command) throws IOException {
        ParcelFileDescriptor output = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (var in = new ParcelFileDescriptor.AutoCloseInputStream(output)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
