package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.collision.mirror.CgMirrorColumn;

import javax.annotation.Nullable;

/**
 * What each loader's {@code ChunkWriteHook} mixin adds to {@code LevelChunk}: the level mirror's column while the chunk
 * is watched. A chunk that is not one never reached the hook, and its level is not mirrored.
 */
public interface MirroredChunk {

    @Nullable
    CgMirrorColumn crystalgraphics$column();

    void crystalgraphics$column(@Nullable CgMirrorColumn column);
}
