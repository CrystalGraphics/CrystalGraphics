package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.platform.CgService;

import javax.annotation.Nullable;

/**
 * What a host answers for the level mirror: where a level's chunks are, a copy of a section's states, and what each
 * state is. Its write hook calls {@link CgMirrorColumn#write} before every block write of an attached chunk, and
 * {@link CgMirrorColumn#unloaded} when the chunk unloads.
 *
 * <pre>{@code
 * // a host, once, on both sides
 * CgPlatform.provide(CgMirrorHost.SERVICE, new MirrorHostModern());
 *
 * // its hook, at the head of the chunk's block write (a mixin)
 * CgMirrorColumn column = this.crystalgraphics$column;
 * if (column != null) column.write(pos.getX(), pos.getY(), pos.getZ(), state, state.getBlock().hasDynamicShape());
 * }</pre>
 *
 * <ul>
 *   <li>Everything here runs on the level's thread but {@link #states} and {@link #describe}, which a worker calls on
 *       the copies and states the level thread handed it.</li>
 *   <li>{@link #mirrors} answers false where the hook did not apply (a dev run without the mixin): the level then has
 *       no mirror, and snapshots copy as they would without one.</li>
 *   <li>{@link #NONE} mirrors nothing.</li>
 * </ul>
 */
public interface CgMirrorHost {

    CgMirrorHost NONE = new CgMirrorHost() {
        @Override public boolean mirrors(Object level) { return false; }
        @Override public int minSection(Object level) { return 0; }
        @Override public int sections(Object level) { return 0; }
        @Override @Nullable public Object chunk(Object level, int chunkX, int chunkZ) { return null; }
        @Override public boolean attach(Object chunk, @Nullable CgMirrorColumn column) { return false; }
        @Override @Nullable public Object copy(Object chunk, int section) { return null; }
        @Override public void states(Object copy, Object[] out) { }
        @Override public void describe(Object state, CgMirrorShape out) { out.clear(); }
        @Override public void resolveAt(Object level, int x, int y, int z, CgMirrorShape out) { out.clear(); }
        @Override public boolean positionalOnly() { return false; }
    };

    CgService<CgMirrorHost> SERVICE = CgService.of("crystalgraphics:level_mirror", NONE);

    /** Whether {@code level} is a server level whose block writes reach the hook. */
    boolean mirrors(Object level);

    /** The lowest section of {@code level}'s columns, as a block y shifted right by 4. */
    int minSection(Object level);

    /** How many sections a column of {@code level} holds. */
    int sections(Object level);

    /** The loaded chunk at chunk coordinates {@code (chunkX, chunkZ)}, never loading one: null when it is not loaded. */
    @Nullable
    Object chunk(Object level, int chunkX, int chunkZ);

    /**
     * Points {@code chunk}'s write hook at {@code column}, or detaches it for null. False when the hook never reached
     * this chunk's class.
     */
    boolean attach(Object chunk, @Nullable CgMirrorColumn column);

    /**
     * A private copy of section {@code section} of {@code chunk} (0 the lowest), which another thread may read: null for
     * a section holding only air, or none. Unused by a {@link #positionalOnly} host.
     */
    @Nullable
    Object copy(Object chunk, int section);

    /** A {@link #copy}'s 4096 states into {@code out}, at {@code y << 8 | z << 4 | x}. Any thread. */
    void states(Object copy, Object[] out);

    /**
     * What {@code state} is, asked on a worker: its boxes and fluid when they follow from the state alone, else
     * {@link CgMirrorShape#positional}.
     */
    void describe(Object state, CgMirrorShape out);

    /**
     * Block {@code (x, y, z)}'s shape where it stands, its fluid at its real height, and {@link CgMirrorShape#dynamic}
     * when that shape changes with no write (a moving piston), so it is resolved again at every publish.
     */
    void resolveAt(Object level, int x, int y, int z, CgMirrorShape out);

    /**
     * Whether every block's shape reads its neighbours (legacy Minecraft): a write resolves its cell and six neighbours,
     * and a section's first copy resolves every block, all on the level's thread.
     */
    boolean positionalOnly();
}
