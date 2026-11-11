package com.klethron.tsnet;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.charset.StandardCharsets;

/// Calls the debug library's test probes, listed in probes.go. Tests ignore results they do not check.
final class Probe {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    private Probe() {}

    @CanIgnoreReturnValue
    static String call(String name) {
        return call(name, "");
    }

    /// Passes `argument` as text in standard UTF-8, so paths may contain supplementary characters.
    @CanIgnoreReturnValue
    static String call(String name, Object argument) {
        String result = nativeCall((name + '\0' + argument).getBytes(StandardCharsets.UTF_8));
        if (result.equals("unknown probe")) throw new IllegalArgumentException("Unknown probe " + name);
        return result;
    }

    private static native String nativeCall(byte[] call);
}
