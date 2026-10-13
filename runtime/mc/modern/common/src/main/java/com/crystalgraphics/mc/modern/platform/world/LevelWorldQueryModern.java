package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgBlockCopy;
import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.LightLayer;
//? if >=1.14.3 {
import net.minecraft.world.phys.shapes.Shapes;
//?} else {
/*import net.minecraft.world.phys.AABB;
*///?}

import java.lang.ref.WeakReference;
//? if <1.14.3 {
/*import java.util.List;
*///?}
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link CgWorldQuery} over one level, 1.13.2 to 26.3, a server's included: what each block and column is, read where
 * each version keeps it. Server-safe; {@link LevelQueriesModern} hands one out per level, and the client's
 * {@link WorldQueryModern} extends it with what only a client has (tint, biome colours, sprites), which this answers 0
 * and false. The level's thread; reuses one mutable position and one box consumer, so it allocates nothing but
 * {@link #collisionBoxes}'s box list on 1.13.2, whose consumer interface is obfuscated.
 *
 * <ul>
 *   <li>A block or fluid in a chunk that is not loaded reads as air: a server's level would load the chunk to answer.</li>
 *   <li>1.13.2 has no sound type, light listener or fluid height under these names: it answers {@code SURFACE_OTHER},
 *       block brightness and a source block's height.</li>
 * </ul>
 */
public class LevelWorldQueryModern implements CgWorldQuery {

    private static final AtomicInteger EPOCHS = new AtomicInteger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final FluidState NO_FLUID = Fluids.EMPTY.defaultFluidState();

    final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    /** Weak: the host's cache maps the level to this, and would otherwise keep it forever. */
    private final WeakReference<Level> bound;
    private Level seen;
    private int epoch;
    private float[] copyBoxes = new float[6 * 16];
    //? if >=1.14.3 {
    private final BoxesInto boxesInto = new BoxesInto();
    //?}

    LevelWorldQueryModern(Level bound) {
        this.bound = new WeakReference<>(bound);
    }

    /** The level it answers for; null for none. */
    Level level() {
        return bound.get();
    }

    /** The block at {@link #pos}, set here; air where its chunk is not loaded, which a server would load to answer. */
    BlockState state(Level level, int x, int y, int z) {
        return level.isLoaded(pos.set(x, y, z)) ? level.getBlockState(pos) : AIR;
    }

    private FluidState fluid(Level level, int x, int y, int z) {
        return level.isLoaded(pos.set(x, y, z)) ? level.getFluidState(pos) : NO_FLUID;
    }

    @Override
    public float collisionTop(int x, int y, int z) {
        Level level = level();
        if (level == null) return Float.NaN;
        VoxelShape shape = state(level, x, y, z).getCollisionShape(level, pos);
        return shape.isEmpty() ? Float.NaN : (float) shape.max(Direction.Axis.Y);
    }

    @Override
    public float collisionBottom(int x, int y, int z) {
        Level level = level();
        if (level == null) return Float.NaN;
        VoxelShape shape = state(level, x, y, z).getCollisionShape(level, pos);
        return shape.isEmpty() ? Float.NaN : (float) shape.min(Direction.Axis.Y);
    }

    @Override
    public int collisionBoxes(int x, int y, int z, float[] out) {
        Level level = level();
        if (level == null) return 0;
        return boxesOf(state(level, x, y, z).getCollisionShape(level, pos), out);
    }

    /** A shape's boxes into {@code out}, as {@link #collisionBoxes} answers. */
    private int boxesOf(VoxelShape shape, float[] out) {
        if (shape.isEmpty()) return 0;
        //? if >=1.14.3 {
        boxesInto.out = out;
        boxesInto.count = 0;
        shape.forAllBoxes(boxesInto);
        boxesInto.out = null;
        return boxesInto.count;
        //?} else {
        /*List<AABB> boxes = shape.toAabbs();
        int fit = Math.min(boxes.size(), out.length / 6);
        for (int i = 0; i < fit; i++) {
            AABB b = boxes.get(i);
            out[i * 6] = (float) b.minX;
            out[i * 6 + 1] = (float) b.minY;
            out[i * 6 + 2] = (float) b.minZ;
            out[i * 6 + 3] = (float) b.maxX;
            out[i * 6 + 4] = (float) b.maxY;
            out[i * 6 + 5] = (float) b.maxZ;
        }
        return boxes.size();
        *///?}
    }

    //? if >=1.14.3 {
    /** A shape's boxes into the caller's array, counting those that do not fit, as {@code collisionBoxes} answers. */
    private static final class BoxesInto implements Shapes.DoubleLineConsumer {
        float[] out;
        int count;

        @Override
        public void consume(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            int at = count++ * 6;
            if (at + 6 > out.length) return;
            out[at] = (float) minX;
            out[at + 1] = (float) minY;
            out[at + 2] = (float) minZ;
            out[at + 3] = (float) maxX;
            out[at + 4] = (float) maxY;
            out[at + 5] = (float) maxZ;
        }
    }
    //?}

    @Override
    public float fluidHeight(int x, int y, int z) {
        Level level = level();
        if (level == null) return Float.NaN;
        FluidState fluid = fluid(level, x, y, z);
        return fluid.isEmpty() ? Float.NaN : heightOf(fluid, level);
    }

    /** A fluid's height at {@link #pos}. */
    private float heightOf(FluidState fluid, Level level) {
        //? if >=1.14 {
        return fluid.getHeight(level, pos);
        //?} else {
        /*return 8f / 9f;
        *///?}
    }

    @Override
    public int fluidKind(int x, int y, int z) {
        Level level = level();
        return level == null ? FLUID_NONE : kindOf(fluid(level, x, y, z));
    }

    private static int kindOf(FluidState fluid) {
        if (fluid.isEmpty()) return FLUID_NONE;
        if (fluid.getType().isSame(Fluids.WATER)) return FLUID_WATER;
        return fluid.getType().isSame(Fluids.LAVA) ? FLUID_LAVA : FLUID_NONE;
    }

    /**
     * From the chunks' sections. A block whose collision shape follows from its state alone, the shapes vanilla caches
     * per state ({@code BlockBehaviour.initCache}), is described once per state; a fluid's height also follows from
     * whether the same fluid is above ({@code FlowingFluid.getHeight}), so that is in its key.
     */
    @Override
    public boolean copyBlocks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CgBlockCopy into) {
        Level level = level();
        if (level == null) return true;
        int bottomSection = minY() >> 4;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!level.isLoaded(pos.set(cx << 4, level.getSeaLevel(), cz << 4))) continue;
                int x0 = Math.max(minX, cx << 4), x1 = Math.min(maxX, (cx << 4) + 15);
                int z0 = Math.max(minZ, cz << 4), z1 = Math.min(maxZ, (cz << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) into.loaded(x, z);
                }
                LevelChunkSection[] sections = level.getChunkAt(pos).getSections();
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    int i = sy - bottomSection;
                    if (i < 0 || i >= sections.length) continue;
                    LevelChunkSection section = sections[i];
                    //? if >=1.18.2 {
                    if (section == null || section.hasOnlyAir()) continue;
                    //?} else {
                    /*if (section == null || section.isEmpty()) continue;
                    *///?}
                    int y0 = Math.max(minY, sy << 4), y1 = Math.min(maxY, (sy << 4) + 15);
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int y = y0; y <= y1; y++) copyBlock(level, section, x, y, z, into);
                        }
                    }
                }
            }
        }
        return true;
    }

    private void copyBlock(Level level, LevelChunkSection section, int x, int y, int z, CgBlockCopy into) {
        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
        if (state.isAir()) return;
        FluidState fluid = state.getFluidState();
        int kind = kindOf(fluid);
        int key = CgBlockCopy.NO_KEY;
        if (!state.getBlock().hasDynamicShape()) {
            boolean sameAbove = kind != FLUID_NONE && fluid.getType().isSame((y & 15) < 15
                    ? section.getFluidState(x & 15, (y & 15) + 1, z & 15).getType()
                    : level.getFluidState(pos.set(x, y + 1, z)).getType());
            key = Block.getId(state) << 1 | (sameAbove ? 1 : 0);
        }
        int entry = key < 0 ? -1 : into.entry(key);
        if (entry < 0) {
            pos.set(x, y, z);
            VoxelShape shape = state.getCollisionShape(level, pos);
            int n = boxesOf(shape, copyBoxes);
            if (n * 6 > copyBoxes.length) {
                copyBoxes = new float[n * 6];
                boxesOf(shape, copyBoxes);
            }
            entry = into.add(key, copyBoxes, n, kind, kind == FLUID_NONE ? Float.NaN : heightOf(fluid, level));
        }
        if (entry != CgBlockCopy.AIR) into.set(x, y, z, entry);
    }

    @Override
    public int light(int x, int y, int z) {
        Level level = level();
        if (level == null) return 0;
        pos.set(x, y, z);
        //? if >=1.15 {
        int block = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(pos);
        int sky = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(pos);
        //?} else {
        /*int block = level.getBrightness(LightLayer.BLOCK, pos);
        int sky = level.getBrightness(LightLayer.SKY, pos);
        *///?}
        return block | sky << 4;
    }

    @Override
    public int lightEmission(int x, int y, int z) {
        Level level = level();
        return level == null ? 0 : state(level, x, y, z).getLightEmission();
    }

    @Override
    public int surface(int x, int y, int z) {
        Level level = level();
        if (level == null) return SURFACE_NONE;
        BlockState state = state(level, x, y, z);
        if (state.isAir()) return SURFACE_NONE;
        //? if >=1.14 {
        return surfaceOf(state.getSoundType());
        //?} else {
        /*return SURFACE_OTHER;
        *///?}
    }

    /** The engine's surface kind for Minecraft's sound type: the material a footstep says the block is. */
    static int surfaceOf(SoundType sound) {
        if (sound == SoundType.STONE) return SURFACE_STONE;
        if (sound == SoundType.GRAVEL) return SURFACE_GRAVEL;
        if (sound == SoundType.SAND) return SURFACE_SAND;
        if (sound == SoundType.SNOW) return SURFACE_SNOW;
        if (sound == SoundType.GLASS) return SURFACE_GLASS;
        if (sound == SoundType.WOOD) return SURFACE_WOOD;
        if (sound == SoundType.GRASS) return SURFACE_PLANT;
        if (sound == SoundType.WOOL) return SURFACE_WOOL;
        if (sound == SoundType.METAL || sound == SoundType.ANVIL) return SURFACE_METAL;
        //? if >=1.17 {
        if (sound == SoundType.POWDER_SNOW) return SURFACE_SNOW;
        //?}
        return SURFACE_OTHER;
    }

    @Override
    public float hardness(int x, int y, int z) {
        Level level = level();
        if (level == null) return Float.NaN;
        BlockState state = state(level, x, y, z);
        return state.isAir() ? Float.NaN : state.getDestroySpeed(level, pos);
    }

    @Override
    public int mapColor(int x, int y, int z) {
        Level level = level();
        if (level == null) return 0;
        BlockState state = state(level, x, y, z);
        if (state.isAir()) return 0;
        int col = state.getMapColor(level, pos).col;
        return col == 0 ? 0 : 0xFF000000 | col;
    }

    @Override
    public int tint(int x, int y, int z) {
        return 0;
    }

    @Override
    public int biomeColor(int x, int y, int z, int kind) {
        return 0;
    }

    @Override
    public boolean spriteRect(int x, int y, int z, float[] out) {
        return false;
    }

    @Override
    public int precipitation(int x, int y, int z) {
        Level level = level();
        if (level == null) return PRECIPITATION_NONE;
        pos.set(x, y, z);
        Biome biome = biome(level);
        //? if >=1.21.3 {
        /*Biome.Precipitation p = biome.getPrecipitationAt(pos, level.getSeaLevel());
        *///?} elif >=1.19.4 {
        Biome.Precipitation p = biome.getPrecipitationAt(pos);
        //?} else {
        /*Biome.Precipitation p = biome.getPrecipitation();
        *///?}
        return p == Biome.Precipitation.SNOW ? PRECIPITATION_SNOW : p == Biome.Precipitation.RAIN ? PRECIPITATION_RAIN
                : PRECIPITATION_NONE;
    }

    /** The biome at {@link #pos}. */
    Biome biome(Level level) {
        //? if >=1.18 {
        return level.getBiome(pos).value();
        //?} else {
        /*return level.getBiome(pos);
        *///?}
    }

    @Override
    public int surfaceY(int x, int z, int kind) {
        Level level = level();
        if (level == null || !loaded(x, z)) return Integer.MIN_VALUE;
        Heightmap.Types type = kind == HEIGHT_NO_LEAVES ? Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
                : kind == HEIGHT_OCEAN_FLOOR ? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.MOTION_BLOCKING;
        return level.getHeight(type, x, z);
    }

    @Override
    public boolean loaded(int x, int z) {
        Level level = level();
        return level != null && level.isLoaded(pos.set(x, level.getSeaLevel(), z));
    }

    @Override
    public int minY() {
        Level level = level();
        if (level == null) return 0;
        //? if >=1.21.3 {
        /*return level.getMinY();
        *///?} elif >=1.17 {
        return level.getMinBuildHeight();
        //?} else {
        /*return 0;
        *///?}
    }

    @Override
    public int maxY() {
        Level level = level();
        if (level == null) return 0;
        //? if >=1.21.3 {
        /*return level.getMaxY() + 1;
        *///?} elif >=1.17 {
        return level.getMaxBuildHeight();
        //?} else {
        /*return 256;
        *///?}
    }

    @Override
    public int seaLevel() {
        Level level = level();
        return level == null ? 0 : level.getSeaLevel();
    }

    @Override
    public int levelEpoch() {
        Level level = level();
        if (level != seen) {
            seen = level;
            epoch = level == null ? 0 : EPOCHS.incrementAndGet();
        }
        return epoch;
    }
}
