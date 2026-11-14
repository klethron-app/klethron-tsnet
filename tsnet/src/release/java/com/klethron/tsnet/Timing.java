package com.klethron.tsnet;

/// Compile-time disabled; javac removes all guarded call sites from the release classes.
final class Timing {
    static final boolean ENABLED = false;

    private Timing() {}

    static void event(String scope, long id, long span, String phase, String event, String result) {}
    static void link(String scope, long id, String relatedScope, long relatedId) {}
    static long identity(Object value) { return 0; }
    static void initialize() {}
}
