package com.crystalgraphics.render.graph;

import com.crystalgraphics.render.draw.CgBufferHandle;

import javax.annotation.Nullable;

/**
 * A buffer a frame graph orders work on, the twin of {@link CgGraphTexture}: a kernel writes it, another reads it, a
 * draw's snapshot binds it. A handle at once, on any thread; its storage is the executor's to resolve on the render
 * thread, device-local.
 *
 * <pre>{@code
 * CgBufferDesc desc = CgBufferDesc.elements(capacity, 32, CgBufferUsage.STORAGE);
 * CgGraphBuffer scratch = CgGraphBuffer.transientBuffer("sort.keys", desc);     // this frame only, pooled
 * CgGraphBuffer cells   = CgGraphBuffer.persistent("fluid.cells", desc);         // kept until released
 * CgGraphBuffer state   = CgGraphBuffer.history("particles", desc);              // the newest two versions
 * CgGraphBuffer theirs  = CgGraphBuffer.imported("mesh.vertices", glBuffer, bytes);
 * }</pre>
 *
 * <ul>
 *   <li><b>Transient</b>: storage from a pool for the passes between its first and last use in one frame; its
 *       contents do not survive the frame, and start undefined.</li>
 *   <li><b>Persistent</b>: storage made on first use and kept until {@code recording.release(buffer)}.</li>
 *   <li><b>History</b>: two storages. Every write makes a new version from nothing — write all of it — and a read
 *       sees the newest; {@link #previous()} reads the one before. A kernel stepping a simulation reads it through one
 *       binding and writes it through another; one binding reading and writing a history is refused.</li>
 *   <li><b>Imported</b>: someone else's buffer, used as it is. A reader outside the graph places its own barrier.</li>
 * </ul>
 */
public final class CgGraphBuffer extends CgGraphResource implements CgBufferHandle {

    /** Where its storage comes from. */
    public enum Kind { IMPORTED, TRANSIENT, PERSISTENT, HISTORY }

    private final Kind kind;
    @Nullable
    private final CgBufferDesc desc;
    private final long size;
    /** For {@link #previous()}'s view: the history it reads, else null. */
    @Nullable
    private final CgGraphBuffer of;
    @Nullable
    private CgGraphBuffer previous;

    /** Render thread only: the storage while resolved; a history's two versions and which is newest. */
    private int storage;
    private final int[] versions;
    private int newest;

    private CgGraphBuffer(Kind kind, String name, @Nullable CgBufferDesc desc, long size, int storage,
                          @Nullable CgGraphBuffer of) {
        super(name);
        this.kind = kind;
        this.desc = desc;
        this.size = size;
        this.storage = storage;
        this.of = of;
        this.versions = kind == Kind.HISTORY && of == null ? new int[2] : null;
    }

    /** Storage for this frame only, from the pool. */
    public static CgGraphBuffer transientBuffer(String name, CgBufferDesc desc) {
        return new CgGraphBuffer(Kind.TRANSIENT, name, desc, desc.bytes(), 0, null);
    }

    /** Storage made on first use and kept until released. */
    public static CgGraphBuffer persistent(String name, CgBufferDesc desc) {
        return new CgGraphBuffer(Kind.PERSISTENT, name, desc, desc.bytes(), 0, null);
    }

    /** Two storages made on first use, the newest two versions, kept until released. */
    public static CgGraphBuffer history(String name, CgBufferDesc desc) {
        return new CgGraphBuffer(Kind.HISTORY, name, desc, desc.bytes(), 0, null);
    }

    /** A GL buffer someone else owns, {@code size} bytes of it. */
    public static CgGraphBuffer imported(String name, int glBuffer, long size) {
        if (glBuffer == 0) throw new IllegalArgumentException("'" + name + "' imports buffer 0");
        return new CgGraphBuffer(Kind.IMPORTED, name, null, size, glBuffer, null);
    }

    /** A history's version before its newest: what a draw interpolates from. Read-only. */
    public CgGraphBuffer previous() {
        if (kind != Kind.HISTORY || of != null) throw new IllegalStateException("only a history has a previous version: " + this);
        if (previous == null) previous = new CgGraphBuffer(Kind.HISTORY, name + ".previous", desc, size, 0, this);
        return previous;
    }

    public Kind kind() {
        return kind;
    }

    /** What the graph allocates for it; null for an imported buffer. */
    @Nullable
    public CgBufferDesc desc() {
        return desc;
    }

    /** Its size in bytes. */
    public long size() {
        return size;
    }

    /** Whether this is a history's {@link #previous()} view. */
    public boolean isPreviousVersion() {
        return of != null;
    }

    /** The resource its reads and writes are ordered on: a previous view's history. */
    CgGraphBuffer resource() {
        return of != null ? of : this;
    }

    @Override
    boolean isTransient() {
        return kind == Kind.TRANSIENT;
    }

    @Override
    boolean outlivesFrame() {
        return kind != Kind.TRANSIENT;
    }

    /** Render thread: the buffer a read binds now — a history's newest version, or its previous. 0 if unresolved. */
    @Override
    public int bufferId() {
        if (of != null) return of.versions[1 - of.newest];
        if (versions != null) return versions[newest];
        return storage;
    }

    /** Render thread: the storage a write to a history makes its next version in. */
    int nextVersion() {
        return versions[1 - newest];
    }

    /** Render thread: the version just written becomes the newest. */
    void advance() {
        newest = 1 - newest;
    }

    void resolve(int glBuffer) {
        storage = glBuffer;
    }

    /** A history's storages, made by the executor on first use. */
    int[] versions() {
        return versions;
    }

    @Override
    public String toString() {
        return "CgGraphBuffer(" + kind + " " + name + ")";
    }
}
