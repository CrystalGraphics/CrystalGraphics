package com.crystalgraphics.storage.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * One corner of another store as a key prefix: names are written as {@code <scope>/<name>} and {@link #list()} returns
 * only this scope's, prefix stripped. What {@link CgConfigStorage#scoped} answers unless the store does better.
 *
 * <pre>{@code
 * CgConfigStorage editor = store.scoped("editor");
 * editor.write("settings.json", json);   // stored as "editor/settings.json"
 * }</pre>
 */
public final class CgScopedConfigStorage implements CgConfigStorage {

    private final CgConfigStorage delegate;
    private final String prefix;

    CgScopedConfigStorage(CgConfigStorage delegate, String scope) {
        this.delegate = delegate;
        this.prefix = scope + "/";
    }

    @Override
    @Nullable
    public byte[] readBytes(String name) {
        return delegate.readBytes(prefix + name);
    }

    @Override
    public void writeBytes(String name, byte[] contents) {
        delegate.writeBytes(prefix + name, contents);
    }

    @Override
    public List<String> list() {
        List<String> mine = new ArrayList<>();
        for (String name : delegate.list()) {
            if (name.startsWith(prefix)) mine.add(name.substring(prefix.length()));
        }
        return mine;
    }

    @Override
    public void delete(String name) {
        delegate.delete(prefix + name);
    }

    @Override
    public boolean isWritable() {
        return delegate.isWritable();
    }

    /** The delegate's, unscoped: a cache folder is asked for by name, so sharing the root does not collide. */
    @Override
    @Nullable
    public Path directory() {
        return delegate.directory();
    }
}
