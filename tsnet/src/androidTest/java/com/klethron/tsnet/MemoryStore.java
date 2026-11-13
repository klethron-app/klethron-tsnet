package com.klethron.tsnet;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

class MemoryStore implements StateStore {
    private final Map<String, byte[]> values = new ConcurrentHashMap<>();

    @Override
    public byte @Nullable [] read(String key) throws IOException {
        byte[] value = values.get(key);
        return value == null ? null : value.clone();
    }

    @Override
    public void write(String key, byte @Nullable [] value) throws IOException {
        if (value == null) values.remove(key);
        else values.put(key, value.clone());
    }
}
