package com.crystalgraphics.compute.source;

/** How the kernels of a {@code .compute} use an image it declares in {@code Images { }}. */
public enum CgImageAccess {
    READONLY,
    WRITEONLY,
    READWRITE;

    public boolean readable() { return this != WRITEONLY; }

    public boolean writable() { return this != READONLY; }

    /** The access an {@code Images} token names, or null. */
    public static CgImageAccess of(String token) {
        for (CgImageAccess a : values()) if (a.name().equalsIgnoreCase(token)) return a;
        return null;
    }
}
