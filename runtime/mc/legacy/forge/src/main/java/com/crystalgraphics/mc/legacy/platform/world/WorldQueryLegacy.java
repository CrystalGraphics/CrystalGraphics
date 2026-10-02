package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeColorHelper;
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

/**
 * {@link CgWorldQuery} over the client's level on Forge 1.8.9 to 1.12.2. Client only: provided by
 * {@code PlatformServiceLegacy.register}. Render thread; reuses one mutable position and one box list.
 *
 * <ul>
 *   <li>No per-biome fog or water-fog colour exists before 1.13: those answer 0. The sky colour is the biome's
 *       temperature colour.</li>
 *   <li>Heights: {@code HEIGHT_TOP} is the precipitation height (the first block that blocks movement or holds
 *       liquid); the other two are the top solid block below leaves and liquid alike.</li>
 * </ul>
 */
public final class WorldQueryLegacy implements CgWorldQuery {

    // A block adds only its own boxes, so one mask covering the world serves every cell.
    private static final AxisAlignedBB EVERYWHERE = new AxisAlignedBB(-3.0E7, -1.0E4, -3.0E7, 3.0E7, 1.0E4, 3.0E7);

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final List<AxisAlignedBB> boxes = new ArrayList<>();
    private WorldClient seen;
    private int epoch, epochs;

    private BlockPos at(int x, int y, int z) {
        //? if >=1.9 {
        return pos.setPos(x, y, z);
        //?} else {
        /*return pos.set(x, y, z);
        *///?}
    }

    /** The block's collision boxes in world space, into {@link #boxes}. */
    private List<AxisAlignedBB> boxes(WorldClient world, int x, int y, int z) {
        boxes.clear();
        BlockPos p = at(x, y, z);
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
        WorldClient world = ClientLegacy.world();
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
        WorldClient world = ClientLegacy.world();
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
        WorldClient world = ClientLegacy.world();
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
        WorldClient world = ClientLegacy.world();
        if (world == null) return Float.NaN;
        IBlockState state = world.getBlockState(at(x, y, z));
        Material m = material(state);
        if (!m.isLiquid()) return Float.NaN;
        if (material(world.getBlockState(at(x, y + 1, z))) == m) return 1f;
        if (!(state.getBlock() instanceof BlockLiquid)) return 8f / 9f;
        return 1f - BlockLiquid.getLiquidHeightPercent((Integer) state.getValue(BlockLiquid.LEVEL));
    }

    @Override
    public int fluidKind(int x, int y, int z) {
        WorldClient world = ClientLegacy.world();
        return world == null ? FLUID_NONE : fluidOf(material(world.getBlockState(at(x, y, z))));
    }

    @Override
    public int light(int x, int y, int z) {
        WorldClient world = ClientLegacy.world();
        if (world == null) return 0;
        BlockPos p = at(x, y, z);
        return world.getLightFor(EnumSkyBlock.BLOCK, p) | world.getLightFor(EnumSkyBlock.SKY, p) << 4;
    }

    @Override
    public int lightEmission(int x, int y, int z) {
        WorldClient world = ClientLegacy.world();
        if (world == null) return 0;
        BlockPos p = at(x, y, z);
        //? if >=1.9 {
        return world.getBlockState(p).getLightValue(world, p);
        //?} else {
        /*return world.getBlockState(p).getBlock().getLightValue(world, p);
        *///?}
    }

    @Override
    public int surface(int x, int y, int z) {
        WorldClient world = ClientLegacy.world();
        if (world == null) return SURFACE_NONE;
        BlockPos p = at(x, y, z);
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
        WorldClient world = ClientLegacy.world();
        if (world == null) return Float.NaN;
        BlockPos p = at(x, y, z);
        if (world.isAirBlock(p)) return Float.NaN;
        //? if >=1.9 {
        return world.getBlockState(p).getBlockHardness(world, p);
        //?} else {
        /*return world.getBlockState(p).getBlock().getBlockHardness(world, p);
        *///?}
    }

    @Override
    public int mapColor(int x, int y, int z) {
        WorldClient world = ClientLegacy.world();
        if (world == null) return 0;
        BlockPos p = at(x, y, z);
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
        WorldClient world = ClientLegacy.world();
        if (world == null) return 0;
        BlockPos p = at(x, y, z);
        if (world.isAirBlock(p)) return 0;
        IBlockState state = world.getBlockState(p);
        //? if >=1.9 {
        int c = Minecraft.getMinecraft().getBlockColors().colorMultiplier(state, world, p, 0);
        return c == -1 ? 0 : 0xFF000000 | c;
        //?} else {
        /*// An untinted block answers white here.
        int c = state.getBlock().colorMultiplier(world, p, 0);
        return c == 0xFFFFFF ? 0 : 0xFF000000 | c;
        *///?}
    }

    @Override
    public int biomeColor(int x, int y, int z, int kind) {
        WorldClient world = ClientLegacy.world();
        if (world == null) return 0;
        BlockPos p = at(x, y, z);
        switch (kind) {
            case BIOME_GRASS: return 0xFF000000 | BiomeColorHelper.getGrassColorAtPos(world, p);
            case BIOME_FOLIAGE: return 0xFF000000 | BiomeColorHelper.getFoliageColorAtPos(world, p);
            case BIOME_WATER: return 0xFF000000 | BiomeColorHelper.getWaterColorAtPos(world, p);
            default: break;
        }
        if (kind != BIOME_SKY) return 0;
        //? if >=1.12 {
        Biome biome = world.getBiome(p);
        return 0xFF000000 | biome.getSkyColorByTemp(biome.getTemperature(p));
        //?} elif >=1.9 {
        /*Biome biome = world.getBiome(p);
        return 0xFF000000 | biome.getSkyColorByTemp(biome.getFloatTemperature(p));
        *///?} else {
        /*BiomeGenBase biome = world.getBiomeGenForCoords(p);
        return 0xFF000000 | biome.getSkyColorByTemp(biome.getFloatTemperature(p));
        *///?}
    }

    // As EntityRenderer.renderRainSnow decides: a biome that rains or snows, snow below 0.15.
    @Override
    public int precipitation(int x, int y, int z) {
        WorldClient world = ClientLegacy.world();
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
        WorldClient world = ClientLegacy.world();
        if (world == null || !loaded(x, z)) return Integer.MIN_VALUE;
        BlockPos p = at(x, 0, z);
        return kind == HEIGHT_TOP ? world.getPrecipitationHeight(p).getY() : world.getTopSolidOrLiquidBlock(p).getY();
    }

    @Override
    public boolean spriteRect(int x, int y, int z, float[] out) {
        WorldClient world = ClientLegacy.world();
        if (world == null) return false;
        BlockPos p = at(x, y, z);
        if (world.isAirBlock(p)) return false;
        TextureAtlasSprite sprite = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes()
                .getTexture(world.getBlockState(p));
        if (sprite == null) return false;
        out[0] = sprite.getMinU();
        out[1] = sprite.getMinV();
        out[2] = sprite.getMaxU();
        out[3] = sprite.getMaxV();
        return true;
    }

    @Override
    public boolean loaded(int x, int z) {
        WorldClient world = ClientLegacy.world();
        return world != null && world.isBlockLoaded(at(x, world.getSeaLevel(), z));
    }

    @Override
    public int minY() {
        return 0;
    }

    @Override
    public int maxY() {
        WorldClient world = ClientLegacy.world();
        return world == null ? 0 : world.getHeight();
    }

    @Override
    public int seaLevel() {
        WorldClient world = ClientLegacy.world();
        return world == null ? 0 : world.getSeaLevel();
    }

    @Override
    public int levelEpoch() {
        WorldClient world = ClientLegacy.world();
        if (world != seen) {
            seen = world;
            epoch = world == null ? 0 : ++epochs;
        }
        return epoch;
    }
}
