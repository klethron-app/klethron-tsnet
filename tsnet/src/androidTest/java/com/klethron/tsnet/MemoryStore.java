package com.klethron.tsnet;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class MemoryStore implements StateStore {
    private final Map<String, byte[]> values = new HashMap<>();

    @Override
    public synchronized byte @Nullable [] read(String key) {
        byte[] value = values.get(key);
        return value == null ? null : value.clone();
    }

    @Override
    public synchronized void write(String key, byte @Nullable [] value) {
        if (value == null) values.remove(key);
        else values.put(key, value.clone());
    }
}
