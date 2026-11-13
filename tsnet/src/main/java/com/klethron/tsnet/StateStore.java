package com.klethron.tsnet;

import java.io.IOException;
import org.jspecify.annotations.Nullable;

/// Caller-owned storage for one node's opaque keys and values, including private credentials.
/// Keep it out of backups, exports and sync. Each running node needs exclusive access to its
/// logical store, including across processes; separate wrappers must not share the same records.
///
/// Calls are synchronous and serialized, on worker threads. Do not call node methods from these
/// callbacks. A successful write must be visible to subsequent reads. The caller chooses durability
/// and may handle persistence failures with an in-memory fallback; throwing aborts the node with
/// [IOException].
/// [TailnetNode#close] waits for callbacks to finish; it does not close or erase the store.
public interface StateStore {
    /// Returns a value, or `null` when absent. An empty array is a present value.
    byte @Nullable [] read(String key) throws IOException;

    /// Atomically replaces a value, or deletes it when `value` is `null`.
    void write(String key, byte @Nullable [] value) throws IOException;
}
