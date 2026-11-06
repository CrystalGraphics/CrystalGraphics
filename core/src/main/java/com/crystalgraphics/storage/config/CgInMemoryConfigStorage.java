package com.crystalgraphics.storage.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A {@link CgConfigStorage} in a map, for tests and runs that must not touch the real configuration.
 *
 * <pre>{@code
 * CgInMemoryConfigStorage store = new CgInMemoryConfigStorage();
 * store.setWritable(false);              // reach the read-only path without a read-only disk
 * }</pre>
 */
public final class CgInMemoryConfigStorage implements CgConfigStorage {

    private final Map<String, byte[]> blobs = new LinkedHashMap<>();
    private boolean writable = true;

    @Nullable
    @Override
    public byte[] readBytes(String name) {
        byte[] bytes = blobs.get(name);
        return bytes == null ? null : bytes.clone();
    }

    @Override
    public void writeBytes(String name, byte[] contents) {
        if (!writable) throw new IllegalStateException("Config storage is read-only");
        blobs.put(name, contents.clone());
    }

    @Override
    public List<String> list() {
        return new ArrayList<>(blobs.keySet());
    }

    @Override
    public void delete(String name) {
        blobs.remove(name);
    }

    @Override
    public boolean isWritable() {
        return writable;
    }

    /** Makes every write fail. */
    public CgInMemoryConfigStorage setWritable(boolean writable) {
        this.writable = writable;
        return this;
    }
}
