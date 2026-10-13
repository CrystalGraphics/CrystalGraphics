package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.collision.mirror.CgMirrorColumn;

/**
 * What the legacy {@code ChunkWriteHook} mixin adds to {@code Chunk}: the level mirror's column while the chunk is
 * watched. A chunk that is not one never reached the hook, and its level is not mirrored.
 */
public interface MirroredChunkLegacy {

    CgMirrorColumn crystalgraphics$column();

    void crystalgraphics$column(CgMirrorColumn column);
}
