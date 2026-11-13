package com.klethron.tsnet;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.charset.StandardCharsets;

/// Calls the debug library's test probes, listed in probes.go. Tests ignore results they do not check.
final class Probe {
    static {
        String unused = TailnetNode.tailscaleVersion();
    }

    @CanIgnoreReturnValue
    static String call(String name) {
        return call(name, "");
    }

    @CanIgnoreReturnValue
    static String call(String name, Object argument) {
        String result = nativeCall((name + '\0' + argument).getBytes(StandardCharsets.UTF_8));
        if (result.equals("unknown probe")) throw new IllegalArgumentException("Unknown probe " + name);
        return result;
    }

    static long handle(TailnetNode node) throws ReflectiveOperationException {
        var field = TailnetNode.class.getDeclaredField("handle");
        field.setAccessible(true);
        return field.getLong(node);
    }

    private static native String nativeCall(byte[] call);
}
