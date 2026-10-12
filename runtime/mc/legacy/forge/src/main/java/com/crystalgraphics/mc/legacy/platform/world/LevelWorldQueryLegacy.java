package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
//? if >=1.9 {
import net.minecraft.block.SoundType;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
//?} else {
/*import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.biome.BiomeGenBase;
*///?}

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link CgWorldQuery} over one level on Forge 1.8.9 to 1.12.2, a server's included. Server-safe;
 * {@link LevelQueriesLegacy} hands one out per level, and the client's {@link WorldQueryLegacy} extends it with the
 * tint, biome colours and sprite, which this answers 0 and false. The level's thread; reuses one mutable position and
 * one box list.
 *
 * <ul>
 *   <li>A block in a chunk that is not loaded reads as air: a server's level would load the chunk to answer.</li>
 *   <li>Heights: {@code HEIGHT_TOP} is the precipitation height (the first block that blocks movement or holds
 *       liquid); the other two are the top solid block below leaves and liquid alike.</li>
 * </ul>
 */
public class LevelWorldQueryLegacy implements CgWorldQuery {

    private static final AtomicInteger EPOCHS = new AtomicInteger();

    // A block adds only its own boxes, so one mask covering the world serves every cell.
    private static final AxisAlignedBB EVERYWHERE = new AxisAlignedBB(-3.0E7, -1.0E4, -3.0E7, 3.0E7, 1.0E4, 3.0E7);

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final List<AxisAlignedBB> boxes = new ArrayList<>();
    private final World bound;
    private World seen;
    private int epoch;

    LevelWorldQueryLegacy(World bound) {
        this.bound = bound;
    }

    /** The level it answers for; null for none. */
    World world() {
        return bound;
    }

    BlockPos at(int x, int y, int z) {
        //? if >=1.9 {
        return pos.setPos(x, y, z);
        //?} else {
        /*return pos.set(x, y, z);
        *///?}
    }

    /** {@link #at}, or null where the chunk is not loaded: a server's level would load it to answer. */
    BlockPos loadedAt(World world, int x, int y, int z) {
        BlockPos p = at(x, y, z);
        return world.isBlockLoaded(p) ? p : null;
    }

    /** The block's collision boxes in world space, into {@link #boxes}; none where it is not loaded. */
    private List<AxisAlignedBB> boxes(World world, int x, int y, int z) {
        boxes.clear();
        BlockPos p = loadedAt(world, x, y, z);
        if (p == null) return boxes;
        IBlockState state = world.getBlockState(p);
        //? if >=1.12 {
        state.addCollisionBoxToList(world, p, EVERYWHERE, boxes, null, false);
        //?} elif >=1.9 {
        /*state.addCollisionBoxToList(world, p, EVERYWHERE, boxes, null);
        *///?} else {
        /*state.getBlock().addCollisionBoxesToList(world, p, state, EVERYWHERE, boxes, null);
        *///?}
        return boxes;
    }

    @Override
    public float collisionTop(int x, int y, int z) {
        World world = world();
        if (world == null) return Float.NaN;
        float top = Float.NaN;
        for (AxisAlignedBB b : boxes(world, x, y, z)) {
            float t = (float) (b.maxY - y);
            if (!(t <= top)) top = t;
        }
        return top;
    }

    @Override
    public float collisionBottom(int x, int y, int z) {
        World world = world();
        if (world == null) return Float.NaN;
        float bottom = Float.NaN;
        for (AxisAlignedBB b : boxes(world, x, y, z)) {
            float t = (float) (b.minY - y);
            if (!(t >= bottom)) bottom = t;
        }
        return bottom;
    }

    @Override
    public int collisionBoxes(int x, int y, int z, float[] out) {
        World world = world();
        if (world == null) return 0;
        List<AxisAlignedBB> list = boxes(world, x, y, z);
        int fit = Math.min(list.size(), out.length / 6);
        for (int i = 0; i < fit; i++) {
            AxisAlignedBB b = list.get(i);
            out[i * 6] = (float) (b.minX - x);
            out[i * 6 + 1] = (float) (b.minY - y);
            out[i * 6 + 2] = (float) (b.minZ - z);
            out[i * 6 + 3] = (float) (b.maxX - x);
            out[i * 6 + 4] = (float) (b.maxY - y);
            out[i * 6 + 5] = (float) (b.maxZ - z);
        }
        return list.size();
    }

    static Material material(IBlockState state) {
        //? if >=1.9 {
        return state.getMaterial();
        //?} else {
        /*return state.getBlock().getMaterial();
        *///?}
    }

    static int fluidOf(Material m) {
        //? if >=1.9 {
        if (m == Material.WATER) return FLUID_WATER;
        return m == Material.LAVA ? FLUID_LAVA : FLUID_NONE;
        //?} else {
        /*if (m == Material.water) return FLUID_WATER;
        return m == Material.lava ? FLUID_LAVA : FLUID_NONE;
        *///?}
    }

    @Override
    public float fluidHeight(int x, int y, int z) {
        World world = world();
        if (world == null || loadedAt(world, x, y, z) == null) return Float.NaN;
        IBlockState state = world.getBlockState(at(x, y, z));
        Material m = material(state);
        if (!m.isLiquid()) return Float.NaN;
        if (material(world.getBlockState(at(x, y + 1, z))) == m) return 1f;
        if (!(state.getBlock() instanceof BlockLiquid)) return 8f / 9f;
        return 1f - BlockLiquid.getLiquidHeightPercent((Integer) state.getValue(BlockLiquid.LEVEL));
    }

    @Override
    public int fluidKind(int x, int y, int z) {
        World world = world();
        BlockPos p = world == null ? null : loadedAt(world, x, y, z);
        return p == null ? FLUID_NONE : fluidOf(material(world.getBlockState(p)));
    }

    @Override
    public int light(int x, int y, int z) {
        World world = world();
        BlockPos p = world == null ? null : loadedAt(world, x, y, z);
        if (p == null) return 0;
        return world.getLightFor(EnumSkyBlock.BLOCK, p) | world.getLightFor(EnumSkyBlock.SKY, p) << 4;
    }

    @Override
    public int lightEmission(int x, int y, int z) {
        World world = world();
        BlockPos p = world == null ? null : loadedAt(world, x, y, z);
        if (p == null) return 0;
        //? if >=1.9 {
        return world.getBlockState(p).getLightValue(world, p);
        //?} else {
        /*return world.getBlockState(p).getBlock().getLightValue(world, p);
        *///?}
    }

    @Override
    public int surface(int x, int y, int z) {
        World world = world();
        BlockPos p = world == null ? null : loadedAt(world, x, y, z);
        if (p == null) return SURFACE_NONE;
        return world.isAirBlock(p) ? SURFACE_NONE : surfaceOf(world.getBlockState(p), world, p);
    }

    /** The engine's surface kind for the block's sound type: the material a footstep says the block is. */
    static int surfaceOf(IBlockState state, World world, BlockPos p) {
        //? if >=1.9 {
        SoundType sound = state.getBlock().getSoundType(state, world, p, null);
        if (sound == SoundType.STONE) return SURFACE_STONE;
        if (sound == SoundType.GROUND) return SURFACE_GRAVEL;
        if (sound == SoundType.SAND) return SURFACE_SAND;
        if (sound == SoundType.SNOW) return SURFACE_SNOW;
        if (sound == SoundType.GLASS) return SURFACE_GLASS;
        if (sound == SoundType.WOOD) return SURFACE_WOOD;
        if (sound == SoundType.PLANT) return SURFACE_PLANT;
        if (sound == SoundType.CLOTH) return SURFACE_WOOL;
        if (sound == SoundType.METAL || sound == SoundType.ANVIL) return SURFACE_METAL;
        //?} else {
        /*Block.SoundType sound = state.getBlock().stepSound;
        if (sound == Block.soundTypeStone || sound == Block.soundTypePiston) return SURFACE_STONE;
        if (sound == Block.soundTypeGravel) return SURFACE_GRAVEL;
        if (sound == Block.soundTypeSand) return SURFACE_SAND;
        if (sound == Block.soundTypeSnow) return SURFACE_SNOW;
        if (sound == Block.soundTypeGlass) return SURFACE_GLASS;
        if (sound == Block.soundTypeWood) return SURFACE_WOOD;
        if (sound == Block.soundTypeGrass) return SURFACE_PLANT;
        if (sound == Block.soundTypeCloth) return SURFACE_WOOL;
        if (sound == Block.soundTypeMetal || sound == Block.soundTypeAnvil) return SURFACE_METAL;
        *///?}
        return SURFACE_OTHER;
    }

    @Override
    public float hardness(int x, int y, int z) {
        World world = world();
        BlockPos p = world == null ? null : loadedAt(world, x, y, z);
        if (p == null || world.isAirBlock(p)) return Float.NaN;
        //? if >=1.9 {
        return world.getBlockState(p).getBlockHardness(world, p);
        //?} else {
        /*return world.getBlockState(p).getBlock().getBlockHardness(world, p);
        *///?}
    }

    @Override
    public int mapColor(int x, int y, int z) {
        World world = world();
        BlockPos p = world == null ? null : loadedAt(world, x, y, z);
        if (p == null) return 0;
        return world.isAirBlock(p) ? 0 : mapColorOf(world.getBlockState(p), world, p);
    }

    /** The block's map colour as opaque ARGB, 0 where it has none. */
    static int mapColorOf(IBlockState state, World world, BlockPos p) {
        //? if >=1.12 {
        int col = state.getMapColor(world, p).colorValue;
        //?} elif >=1.9 {
        /*int col = state.getMapColor().colorValue;
        *///?} else {
        /*int col = state.getBlock().getMapColor(state).colorValue;
        *///?}
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

    // As EntityRenderer.renderRainSnow decides: a biome that rains or snows, snow below 0.15.
    @Override
    public int precipitation(int x, int y, int z) {
        World world = world();
        if (world == null) return PRECIPITATION_NONE;
        BlockPos p = at(x, y, z);
        //? if >=1.12 {
        Biome biome = world.getBiome(p);
        float temperature = biome.getTemperature(p);
        //?} elif >=1.9 {
        /*Biome biome = world.getBiome(p);
        float temperature = biome.getFloatTemperature(p);
        *///?} else {
        /*BiomeGenBase biome = world.getBiomeGenForCoords(p);
        float temperature = biome.getFloatTemperature(p);
        *///?}
        if (!biome.canRain() && !biome.getEnableSnow()) return PRECIPITATION_NONE;
        return temperature < 0.15f ? PRECIPITATION_SNOW : PRECIPITATION_RAIN;
    }

    @Override
    public int surfaceY(int x, int z, int kind) {
        World world = world();
        if (world == null || !loaded(x, z)) return Integer.MIN_VALUE;
        BlockPos p = at(x, 0, z);
        return kind == HEIGHT_TOP ? world.getPrecipitationHeight(p).getY() : world.getTopSolidOrLiquidBlock(p).getY();
    }

    @Override
    public boolean loaded(int x, int z) {
        World world = world();
        return world != null && world.isBlockLoaded(at(x, world.getSeaLevel(), z));
    }

    @Override
    public int minY() {
        return 0;
    }

    @Override
    public int maxY() {
        World world = world();
        return world == null ? 0 : world.getHeight();
    }

    @Override
    public int seaLevel() {
        World world = world();
        return world == null ? 0 : world.getSeaLevel();
    }

    @Override
    public int levelEpoch() {
        World world = world();
        if (world != seen) {
            seen = world;
            epoch = world == null ? 0 : EPOCHS.incrementAndGet();
        }
        return epoch;
    }
}
