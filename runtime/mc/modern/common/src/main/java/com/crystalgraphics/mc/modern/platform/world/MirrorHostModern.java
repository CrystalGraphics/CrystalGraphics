package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.collision.mirror.CgMirrorColumn;
import com.crystalgraphics.collision.mirror.CgMirrorHost;
import com.crystalgraphics.collision.mirror.CgMirrorShape;
import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
//? if >=1.18.2 {
import net.minecraft.world.level.chunk.PalettedContainer;
//?}
//? if >=1.14.3 {
import net.minecraft.world.level.EmptyBlockGetter;
//?}
//? if <1.14.3 {
/*import net.minecraft.world.phys.AABB;
*///?}

import javax.annotation.Nullable;

/**
 * {@link CgMirrorHost} over a server level, 1.14.4 to 26.3: its loaded chunks, a section's states, and each state's
 * shape, read off the shape Minecraft caches per state wherever it does not depend on where the block stands. Provided
 * by {@code NetworkModern} on both sides; each loader's {@code ChunkWriteHook} mixin calls the columns.
 *
 * <ul>
 *   <li>Mirrors nothing below 1.14.4 (no loaded-chunk lookup that never loads), nor a client's level, nor any level
 *       where the mixin did not apply ({@link MirroredChunk}).</li>
 *   <li>Below 1.18.2 a section has no copy of its own, so a first copy reads the 4,096 states into an array.</li>
 *   <li>{@link #describe} runs on a worker: it reads only the state's cached shape and its fluid's own height.</li>
 * </ul>
 */
public final class MirrorHostModern implements CgMirrorHost {

    /** Whether the mixin reached {@code LevelChunk}: asked at the first mirror, once the game's classes are loaded. */
    private Boolean hooked;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BoxesInto levelBoxes = new BoxesInto();

    @Override
    public boolean mirrors(Object level) {
        //? if >=1.14.4 {
        if (!(level instanceof ServerLevel)) return false;
        if (hooked == null) hooked = MirroredChunk.class.isAssignableFrom(LevelChunk.class);
        return hooked;
        //?} else {
        /*return false;
        *///?}
    }

    @Override
    public int minSection(Object level) {
        //? if >=1.21.3 {
        /*return ((ServerLevel) level).getMinSectionY();
        *///?} elif >=1.17 {
        return ((ServerLevel) level).getMinSection();
        //?} else {
        /*return 0;
        *///?}
    }

    @Override
    public int sections(Object level) {
        //? if >=1.17 {
        return ((ServerLevel) level).getSectionsCount();
        //?} else {
        /*return 16;
        *///?}
    }

    @Override
    @Nullable
    public Object chunk(Object level, int chunkX, int chunkZ) {
        //? if >=1.14.4 {
        return ((ServerLevel) level).getChunkSource().getChunkNow(chunkX, chunkZ);
        //?} else {
        /*return null;
        *///?}
    }

    @Override
    public boolean attach(Object chunk, @Nullable CgMirrorColumn column) {
        if (!(chunk instanceof MirroredChunk)) return false;
        ((MirroredChunk) chunk).crystalgraphics$column(column);
        return true;
    }

    @Override
    @Nullable
    public Object copy(Object chunk, int section) {
        LevelChunkSection[] sections = ((LevelChunk) chunk).getSections();
        if (section < 0 || section >= sections.length) return null;
        LevelChunkSection s = sections[section];
        //? if >=1.18.2 {
        if (s == null || s.hasOnlyAir()) return null;
        return s.getStates().copy();
        //?} else {
        /*if (s == null || s.isEmpty()) return null;
        BlockState[] states = new BlockState[4096];
        for (int cell = 0; cell < 4096; cell++) states[cell] = s.getBlockState(cell & 15, cell >> 8, cell >> 4 & 15);
        return states;
        *///?}
    }

    @Override
    @SuppressWarnings("unchecked")
    public void states(Object copy, Object[] out) {
        //? if >=1.18.2 {
        PalettedContainer<BlockState> c = (PalettedContainer<BlockState>) copy;
        for (int cell = 0; cell < 4096; cell++) out[cell] = c.get(cell & 15, cell >> 8, cell >> 4 & 15);
        //?} else {
        /*System.arraycopy((BlockState[]) copy, 0, out, 0, 4096);
        *///?}
    }

    @Override
    public void describe(Object state, CgMirrorShape out) {
        BlockState s = (BlockState) state;
        out.clear();
        if (s.getBlock().hasDynamicShape()) {
            out.positional();
            return;
        }
        // the shape vanilla caches per state (BlockBehaviour.initCache), the one its own context-free collision reads
        //? if >=1.14.3 {
        new BoxesInto().into(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), out);
        //?} else {
        /*out.positional();
        *///?}
        FluidState fluid = s.getFluidState();
        int kind = kindOf(fluid);
        //? if >=1.14.3 {
        if (kind != CgWorldQuery.FLUID_NONE) out.fluid(kind, fluid.getOwnHeight(), true);
        //?} else {
        /*if (kind != CgWorldQuery.FLUID_NONE) out.fluid(kind, 8f / 9f, false);
        *///?}
    }

    @Override
    public void resolveAt(Object level, int x, int y, int z, CgMirrorShape out) {
        ServerLevel l = (ServerLevel) level;
        BlockState s = l.getBlockState(pos.set(x, y, z));
        out.clear();
        levelBoxes.into(s.getCollisionShape(l, pos), out);
        FluidState fluid = s.getFluidState();
        int kind = kindOf(fluid);
        //? if >=1.14.3 {
        if (kind != CgWorldQuery.FLUID_NONE) out.fluid(kind, fluid.getHeight(l, pos), false);
        //?} else {
        /*if (kind != CgWorldQuery.FLUID_NONE) out.fluid(kind, 8f / 9f, false);
        *///?}
        // a block entity moves it with no write (a piston's head, a shulker's lid); bamboo's offset and scaffolding's
        // context-dependence are dynamic shapes too, but stay put until a write
        if (s.getBlock().hasDynamicShape() && s.getBlock() instanceof EntityBlock) out.dynamic();
    }

    @Override
    public boolean positionalOnly() {
        return false;
    }

    private static int kindOf(FluidState fluid) {
        if (fluid.isEmpty()) return CgWorldQuery.FLUID_NONE;
        if (fluid.getType().isSame(Fluids.WATER)) return CgWorldQuery.FLUID_WATER;
        return fluid.getType().isSame(Fluids.LAVA) ? CgWorldQuery.FLUID_LAVA : CgWorldQuery.FLUID_NONE;
    }

    /** A shape's boxes into a {@link CgMirrorShape}. */
    //? if >=1.14.3 {
    private static final class BoxesInto implements Shapes.DoubleLineConsumer {
        private CgMirrorShape out;

        void into(VoxelShape shape, CgMirrorShape into) {
            if (shape.isEmpty()) return;
            out = into;
            shape.forAllBoxes(this);
            out = null;
        }

        @Override
        public void consume(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            out.box(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
    //?} else {
    /*private static final class BoxesInto {
        void into(VoxelShape shape, CgMirrorShape into) {
            for (AABB b : shape.toAabbs()) into.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        }
    }
    *///?}
}
