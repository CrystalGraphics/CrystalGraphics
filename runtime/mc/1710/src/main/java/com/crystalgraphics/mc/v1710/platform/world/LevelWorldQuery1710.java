package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link CgWorldQuery} over one level on Minecraft 1.7.10, a server's included, where a block is a {@link Block} and a
 * metadata nibble. Server-safe; {@link LevelQueries1710} hands one out per level, and the client's
 * {@link WorldQuery1710} extends it with the tint, biome colours and sprite, which this answers 0 and false. The
 * level's thread; reuses one box list.
 *
 * <ul>
 *   <li>Heights: {@code HEIGHT_TOP} is the precipitation height; the other two are the top solid block below leaves and
 *       liquid alike. The sea level is 63, which this version hard-codes.</li>
 * </ul>
 */
public class LevelWorldQuery1710 implements CgWorldQuery {

    private static final int SEA_LEVEL = 63;
    private static final AtomicInteger EPOCHS = new AtomicInteger();
    // A block adds only its own boxes, so one mask covering the world serves every cell.
    private static final AxisAlignedBB EVERYWHERE = AxisAlignedBB.getBoundingBox(-3.0E7, -1.0E4, -3.0E7, 3.0E7, 1.0E4, 3.0E7);

    private final List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
    private final World bound;
    private World seen;
    private int epoch;

    LevelWorldQuery1710(World bound) {
        this.bound = bound;
    }

    /** The level it answers for; null for none. */
    World world() {
        return bound;
    }

    /** The block's collision boxes in world space, into {@link #boxes}. */
    private List<AxisAlignedBB> boxes(World world, int x, int y, int z) {
        boxes.clear();
        world.getBlock(x, y, z).addCollisionBoxesToList(world, x, y, z, EVERYWHERE, boxes, null);
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

    static int fluidOf(Material m) {
        if (m == Material.water) return FLUID_WATER;
        return m == Material.lava ? FLUID_LAVA : FLUID_NONE;
    }

    @Override
    public float fluidHeight(int x, int y, int z) {
        World world = world();
        if (world == null) return Float.NaN;
        Block block = world.getBlock(x, y, z);
        Material m = block.getMaterial();
        if (!m.isLiquid()) return Float.NaN;
        if (world.getBlock(x, y + 1, z).getMaterial() == m) return 1f;
        if (!(block instanceof BlockLiquid)) return 8f / 9f;
        return 1f - BlockLiquid.getLiquidHeightPercent(world.getBlockMetadata(x, y, z));
    }

    @Override
    public int fluidKind(int x, int y, int z) {
        World world = world();
        return world == null ? FLUID_NONE : fluidOf(world.getBlock(x, y, z).getMaterial());
    }

    @Override
    public int light(int x, int y, int z) {
        World world = world();
        if (world == null) return 0;
        return world.getSavedLightValue(EnumSkyBlock.Block, x, y, z) | world.getSavedLightValue(EnumSkyBlock.Sky, x, y, z) << 4;
    }

    @Override
    public int lightEmission(int x, int y, int z) {
        World world = world();
        return world == null ? 0 : world.getBlock(x, y, z).getLightValue(world, x, y, z);
    }

    @Override
    public int surface(int x, int y, int z) {
        World world = world();
        if (world == null) return SURFACE_NONE;
        Block block = world.getBlock(x, y, z);
        return block.getMaterial() == Material.air ? SURFACE_NONE : surfaceOf(block);
    }

    /** The engine's surface kind for the block's step sound: the material a footstep says the block is. */
    static int surfaceOf(Block block) {
        Block.SoundType sound = block.stepSound;
        if (sound == Block.soundTypeStone || sound == Block.soundTypePiston) return SURFACE_STONE;
        if (sound == Block.soundTypeGravel) return SURFACE_GRAVEL;
        if (sound == Block.soundTypeSand) return SURFACE_SAND;
        if (sound == Block.soundTypeSnow) return SURFACE_SNOW;
        if (sound == Block.soundTypeGlass) return SURFACE_GLASS;
        if (sound == Block.soundTypeWood) return SURFACE_WOOD;
        if (sound == Block.soundTypeGrass) return SURFACE_PLANT;
        if (sound == Block.soundTypeCloth) return SURFACE_WOOL;
        if (sound == Block.soundTypeMetal || sound == Block.soundTypeAnvil) return SURFACE_METAL;
        return SURFACE_OTHER;
    }

    @Override
    public float hardness(int x, int y, int z) {
        World world = world();
        if (world == null) return Float.NaN;
        Block block = world.getBlock(x, y, z);
        return block.getMaterial() == Material.air ? Float.NaN : block.getBlockHardness(world, x, y, z);
    }

    @Override
    public int mapColor(int x, int y, int z) {
        World world = world();
        if (world == null) return 0;
        Block block = world.getBlock(x, y, z);
        return block.getMaterial() == Material.air ? 0 : mapColorOf(block, world.getBlockMetadata(x, y, z));
    }

    /** The block's map colour as opaque ARGB, 0 where it has none. */
    static int mapColorOf(Block block, int meta) {
        int col = block.getMapColor(meta).colorValue;
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
        BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
        if (!biome.canSpawnLightningBolt() && !biome.getEnableSnow()) return PRECIPITATION_NONE;
        return biome.getFloatTemperature(x, y, z) < 0.15f ? PRECIPITATION_SNOW : PRECIPITATION_RAIN;
    }

    @Override
    public int surfaceY(int x, int z, int kind) {
        World world = world();
        if (world == null || !loaded(x, z)) return Integer.MIN_VALUE;
        return kind == HEIGHT_TOP ? world.getPrecipitationHeight(x, z) : world.getTopSolidOrLiquidBlock(x, z);
    }

    @Override
    public boolean loaded(int x, int z) {
        World world = world();
        return world != null && world.blockExists(x, SEA_LEVEL, z);
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
        return world() == null ? 0 : SEA_LEVEL;
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
