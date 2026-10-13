package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.collision.mirror.CgMirrorColumn;
import com.crystalgraphics.collision.mirror.CgMirrorHost;
import com.crystalgraphics.collision.mirror.CgMirrorShape;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * {@link CgMirrorHost} over a server level on 1.7.10. A block's boxes may read its neighbours and its metadata (a
 * fence's arms), so each is resolved where it stands, on the level's thread, through the level's own block queries; a
 * moving piston changes with no write and is resolved every publish. Provided by {@code Network1710} on both sides.
 */
public final class MirrorHost1710 implements CgMirrorHost {

    private static final int SEA_LEVEL = 63;

    private float[] boxes = new float[6 * 16];
    /** Whether the mixin reached {@code Chunk}: asked at the first mirror, once the game's classes are loaded. */
    private Boolean hooked;

    @Override
    public boolean mirrors(Object level) {
        if (!(level instanceof WorldServer)) return false;
        if (hooked == null) hooked = MirroredChunk1710.class.isAssignableFrom(Chunk.class);
        return hooked;
    }

    @Override
    public int minSection(Object level) {
        return 0;
    }

    @Override
    public int sections(Object level) {
        return 16;
    }

    @Override
    public Object chunk(Object level, int chunkX, int chunkZ) {
        World world = (World) level;
        return world.blockExists(chunkX << 4, SEA_LEVEL, chunkZ << 4) ? world.getChunkFromChunkCoords(chunkX, chunkZ) : null;
    }

    @Override
    public boolean attach(Object chunk, CgMirrorColumn column) {
        if (!(chunk instanceof MirroredChunk1710)) return false;
        ((MirroredChunk1710) chunk).crystalgraphics$column(column);
        return true;
    }

    @Override
    public Object copy(Object chunk, int section) {
        return null;
    }

    @Override
    public void states(Object copy, Object[] out) {
    }

    @Override
    public void describe(Object state, CgMirrorShape out) {
        out.clear().positional();
    }

    @Override
    public void resolveAt(Object level, int x, int y, int z, CgMirrorShape out) {
        CgWorldQuery world = CgPlatform.get(CgLevelQueries.SERVICE).world(level);
        out.clear().positional();
        int n = world.collisionBoxes(x, y, z, boxes);
        if (n * 6 > boxes.length) {
            boxes = new float[n * 6];
            n = world.collisionBoxes(x, y, z, boxes);
        }
        for (int i = 0; i < n * 6; i += 6) out.box(boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3], boxes[i + 4], boxes[i + 5]);
        int kind = world.fluidKind(x, y, z);
        if (kind != CgWorldQuery.FLUID_NONE) out.fluid(kind, world.fluidHeight(x, y, z), false);
        if (((World) level).getBlock(x, y, z) == Blocks.piston_extension) out.dynamic();
    }

    @Override
    public boolean positionalOnly() {
        return true;
    }
}
