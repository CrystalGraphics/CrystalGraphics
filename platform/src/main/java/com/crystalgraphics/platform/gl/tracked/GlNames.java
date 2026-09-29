package com.crystalgraphics.platform.gl.tracked;

import java.util.ArrayList;
import java.util.List;

/** GL names of one kind: 1 up, 0 never handed out. A deleted name is not reused, so a stale one is caught. */
final class GlNames<T> {

    private final List<T> objects = new ArrayList<>();
    private final String kind;

    GlNames(String kind) {
        this.kind = kind;
        objects.add(null);
    }

    /** The name the next {@link #add} hands out, for an object that holds its own name. */
    int next() {
        return objects.size();
    }

    int add(T object) {
        objects.add(object);
        return objects.size() - 1;
    }

    /** The object, or {@code null} for 0. */
    T get(int name) {
        if (name == 0) return null;
        T o = name < objects.size() ? objects.get(name) : null;
        if (o == null) throw new IllegalArgumentException(kind + " " + name + " does not exist");
        return o;
    }

    boolean exists(int name) {
        return name > 0 && name < objects.size() && objects.get(name) != null;
    }

    /** The object deleted, or {@code null} for 0 or an unknown name, which GL ignores. */
    T remove(int name) {
        if (!exists(name)) return null;
        return objects.set(name, null);
    }
}
