package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.collision.mirror.CgMirrorColumn;

/**
 * What the 1.7.10 {@code ChunkWriteHook} mixin adds to {@code Chunk}: the level mirror's column while the chunk is
 * watched. A chunk that is not one never reached the hook, and its level is not mirrored.
 */
public interface MirroredChunk1710 {

    CgMirrorColumn crystalgraphics$column();

    void crystalgraphics$column(CgMirrorColumn column);
}
