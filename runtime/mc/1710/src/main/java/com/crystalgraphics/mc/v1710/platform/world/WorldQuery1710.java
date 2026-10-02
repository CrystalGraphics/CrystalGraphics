package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link CgWorldQuery} over the client's level on Minecraft 1.7.10, where a block is a {@link Block} and a metadata
 * nibble. Client only: provided by {@code PlatformService1710.onPreInit}. Render thread; reuses one box list.
 *
 * <ul>
 *   <li>No per-biome fog or water-fog colour exists: those answer 0. The sky colour is the biome's temperature colour;
 *       the water colour is the biome's water multiplier.</li>
 *   <li>Heights: {@code HEIGHT_TOP} is the precipitation height; the other two are the top solid block below leaves and
 *       liquid alike. The sea level is 63, which this version hard-codes.</li>
 *   <li>The sprite is the block's bottom face, the icon its breaking particles use.</li>
 * </ul>
 */
public final class WorldQuery1710 implements CgWorldQuery {

    private static final int SEA_LEVEL = 63;
    // A block adds only its own boxes, so one mask covering the world serves every cell.
    private static final AxisAlignedBB EVERYWHERE = AxisAlignedBB.getBoundingBox(-3.0E7, -1.0E4, -3.0E7, 3.0E7, 1.0E4, 3.0E7);

    private final List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
    private WorldClient seen;
    private int epoch, epochs;

    private static WorldClient world() {
        return Minecraft.getMinecraft().theWorld;
    }

    /** The block's collision boxes in world space, into {@link #boxes}. */
    private List<AxisAlignedBB> boxes(WorldClient world, int x, int y, int z) {
        boxes.clear();
        world.getBlock(x, y, z).addCollisionBoxesToList(world, x, y, z, EVERYWHERE, boxes, null);
        return boxes;
    }

    @Override
    public float collisionTop(int x, int y, int z) {
        WorldClient world = world();
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
        WorldClient world = world();
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
        WorldClient world = world();
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
        WorldClient world = world();
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
        WorldClient world = world();
        return world == null ? FLUID_NONE : fluidOf(world.getBlock(x, y, z).getMaterial());
    }

    @Override
    public int light(int x, int y, int z) {
        WorldClient world = world();
        if (world == null) return 0;
        return world.getSavedLightValue(EnumSkyBlock.Block, x, y, z) | world.getSavedLightValue(EnumSkyBlock.Sky, x, y, z) << 4;
    }

    @Override
    public int lightEmission(int x, int y, int z) {
        WorldClient world = world();
        return world == null ? 0 : world.getBlock(x, y, z).getLightValue(world, x, y, z);
    }

    @Override
    public int surface(int x, int y, int z) {
        WorldClient world = world();
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
        WorldClient world = world();
        if (world == null) return Float.NaN;
        Block block = world.getBlock(x, y, z);
        return block.getMaterial() == Material.air ? Float.NaN : block.getBlockHardness(world, x, y, z);
    }

    @Override
    public int mapColor(int x, int y, int z) {
        WorldClient world = world();
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
        WorldClient world = world();
        if (world == null) return 0;
        Block block = world.getBlock(x, y, z);
        if (block.getMaterial() == Material.air) return 0;
        // An untinted block answers white here.
        int c = block.colorMultiplier(world, x, y, z);
        return c == 0xFFFFFF ? 0 : 0xFF000000 | c;
    }

    @Override
    public int biomeColor(int x, int y, int z, int kind) {
        WorldClient world = world();
        if (world == null) return 0;
        BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
        switch (kind) {
            case BIOME_GRASS: return 0xFF000000 | biome.getBiomeGrassColor(x, y, z);
            case BIOME_FOLIAGE: return 0xFF000000 | biome.getBiomeFoliageColor(x, y, z);
            case BIOME_WATER: return 0xFF000000 | biome.waterColorMultiplier;
            case BIOME_SKY: return 0xFF000000 | biome.getSkyColorByTemp(biome.getFloatTemperature(x, y, z));
            default: return 0;
        }
    }

    // As EntityRenderer.renderRainSnow decides: a biome that rains or snows, snow below 0.15.
    @Override
    public int precipitation(int x, int y, int z) {
        WorldClient world = world();
        if (world == null) return PRECIPITATION_NONE;
        BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
        if (!biome.canSpawnLightningBolt() && !biome.getEnableSnow()) return PRECIPITATION_NONE;
        return biome.getFloatTemperature(x, y, z) < 0.15f ? PRECIPITATION_SNOW : PRECIPITATION_RAIN;
    }

    @Override
    public int surfaceY(int x, int z, int kind) {
        WorldClient world = world();
        if (world == null || !loaded(x, z)) return Integer.MIN_VALUE;
        return kind == HEIGHT_TOP ? world.getPrecipitationHeight(x, z) : world.getTopSolidOrLiquidBlock(x, z);
    }

    @Override
    public boolean spriteRect(int x, int y, int z, float[] out) {
        WorldClient world = world();
        if (world == null) return false;
        Block block = world.getBlock(x, y, z);
        if (block.getMaterial() == Material.air) return false;
        IIcon icon = block.getIcon(0, world.getBlockMetadata(x, y, z));
        if (icon == null) return false;
        out[0] = icon.getMinU();
        out[1] = icon.getMinV();
        out[2] = icon.getMaxU();
        out[3] = icon.getMaxV();
        return true;
    }

    @Override
    public boolean loaded(int x, int z) {
        WorldClient world = world();
        return world != null && world.blockExists(x, SEA_LEVEL, z);
    }

    @Override
    public int minY() {
        return 0;
    }

    @Override
    public int maxY() {
        WorldClient world = world();
        return world == null ? 0 : world.getHeight();
    }

    @Override
    public int seaLevel() {
        return world() == null ? 0 : SEA_LEVEL;
    }

    @Override
    public int levelEpoch() {
        WorldClient world = world();
        if (world != seen) {
            seen = world;
            epoch = world == null ? 0 : ++epochs;
        }
        return epoch;
    }
}
