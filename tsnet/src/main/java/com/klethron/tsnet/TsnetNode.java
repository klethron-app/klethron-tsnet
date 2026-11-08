package com.klethron.tsnet;

/// Owns an unstarted native tsnet node. Networking and configuration are not implemented yet.
/// Close explicitly; construction does not create credentials, access storage or join a tailnet.
public final class TsnetNode implements AutoCloseable {
    static {
        System.loadLibrary("klethron_tsnet");
    }

    private long handle;

    public TsnetNode() {
        handle = nativeCreate();
    }

    /// Returns the exact Go module version linked into this binary.
    public static String tailscaleVersion() {
        return nativeTailscaleVersion();
    }

    /// Releases this node. Repeated or concurrent calls are safe and have no additional effect.
    @Override
    public synchronized void close() {
        if (handle != 0) {
            long ownedHandle = handle;
            handle = 0;
            nativeClose(ownedHandle);
        }
    }

    private static native long nativeCreate();

    private static native void nativeClose(long handle);

    private static native String nativeTailscaleVersion();
}
