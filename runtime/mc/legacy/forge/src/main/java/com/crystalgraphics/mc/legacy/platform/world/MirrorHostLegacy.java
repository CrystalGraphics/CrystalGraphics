package com.crystalgraphics.mc.legacy.platform.world;

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
//? if >=1.9 {
import net.minecraft.util.math.BlockPos;
//?} else {
/*import net.minecraft.util.BlockPos;
*///?}

/**
 * {@link CgMirrorHost} over a server level on Forge 1.8.9 to 1.12.2. Every block's shape reads its neighbours here (a
 * fence's arms) and 1.8.9 computes it in mutable {@code Block} fields, so each is resolved where it stands, on the level's
 * thread, through the level's own block queries; a moving piston changes with no write and is resolved every publish.
 * Provided by {@code NetworkLegacy} on both sides.
 */
public final class MirrorHostLegacy implements CgMirrorHost {

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private float[] boxes = new float[6 * 16];
    /** Whether the mixin reached {@code Chunk}: asked at the first mirror, once the game's classes are loaded. */
    private Boolean hooked;

    @Override
    public boolean mirrors(Object level) {
        if (!(level instanceof WorldServer)) return false;
        if (hooked == null) hooked = MirroredChunkLegacy.class.isAssignableFrom(Chunk.class);
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
        //? if >=1.9 {
        if (!world.isBlockLoaded(pos.setPos(chunkX << 4, 64, chunkZ << 4))) return null;
        //?} else {
        /*if (!world.isBlockLoaded(pos.set(chunkX << 4, 64, chunkZ << 4))) return null;
        *///?}
        //? if >=1.12 {
        return world.getChunk(chunkX, chunkZ);
        //?} else {
        /*return world.getChunkFromChunkCoords(chunkX, chunkZ);
        *///?}
    }

    @Override
    public boolean attach(Object chunk, CgMirrorColumn column) {
        if (!(chunk instanceof MirroredChunkLegacy)) return false;
        ((MirroredChunkLegacy) chunk).crystalgraphics$column(column);
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
        //? if >=1.10 {
        if (((World) level).getBlockState(pos.setPos(x, y, z)).getBlock() == Blocks.PISTON_EXTENSION) out.dynamic();
        //?} else {
        /*if (((World) level).getBlockState(pos.set(x, y, z)).getBlock() == Blocks.piston_extension) out.dynamic();
        *///?}
    }

    @Override
    public boolean positionalOnly() {
        return true;
    }
}
