package com.crystalgraphics.collision.mirror;

import javax.annotation.Nullable;

/**
 * One chunk column of a level mirror: what a host's write hook holds while the chunk is watched, and tells of every block
 * write and of the chunk unloading. A mod never makes or holds one.
 *
 * <pre>{@code
 * // the host's mixin on the chunk: a field, the head of its block write, and its unload
 * @Unique private volatile CgMirrorColumn crystalgraphics$column;
 *
 * CgMirrorColumn c = crystalgraphics$column;
 * if (c != null) c.write(pos.getX(), pos.getY(), pos.getZ(), state, state.getBlock().hasDynamicShape());
 *
 * if (!loaded && crystalgraphics$column != null) crystalgraphics$column.unloaded();
 * }</pre>
 *
 * <ul>
 *   <li>The level's thread, before the write lands: the state is the one being written.</li>
 *   <li>Allocates nothing past a section's log growing.</li>
 * </ul>
 */
public final class CgMirrorColumn {

    final CgLevelMirror mirror;
    final int chunkX, chunkZ;
    final long key;
    /** By section, null where nothing watches it. The level thread's, like everything here. */
    final MirrorSection[] sections;
    /** How many watches want each section. */
    final int[] wants;
    /** The chunk its hook is attached to; null while not loaded or not yet found. */
    @Nullable Object chunk;
    /** Whether the column is known unloaded: no chunk at the last look, or it unloaded since. */
    boolean unloaded;

    CgMirrorColumn(CgLevelMirror mirror, int chunkX, int chunkZ, int sections) {
        this.mirror = mirror;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.key = CgLevelMirror.key(chunkX, chunkZ);
        this.sections = new MirrorSection[sections];
        this.wants = new int[sections];
    }

    /**
     * Block {@code (x, y, z)} is about to become {@code state}. {@code dynamic} when its shape may depend on where it
     * stands (a modern block with a dynamic shape, a legacy moving piston): it is resolved at the tick's end, and every
     * tick after while {@link CgMirrorHost#resolveAt} says it moves on its own.
     */
    public void write(int x, int y, int z, @Nullable Object state, boolean dynamic) {
        int i = (y >> 4) - mirror.minSection;
        if (i < 0 || i >= sections.length) return;
        MirrorSection s = sections[i];
        if (s == null || !s.based || s.resync) return;
        int cell = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        if (!s.log.append(cell, state)) {
            s.resync = true;
            mirror.resync(s);
            return;
        }
        if (s.dirtyGeneration != mirror.generation) {
            s.dirtyGeneration = mirror.generation;
            mirror.dirty(s);
        }
        if (mirror.positionalOnly) mirror.positionalWrite(s, cell);
        if (dynamic) mirror.dynamicWrite(s, cell);
        else if (s.dynamicCount > 0) s.removeDynamic(cell);
    }

    /** The chunk unloaded: its sections read as unloaded from the next version until it loads again. */
    public void unloaded() {
        mirror.unloaded(this);
    }
}
