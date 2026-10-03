package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.LightLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//? if >=26.1 {
/*import net.minecraft.client.color.block.BlockTintSource;
*///?}
//? if >=1.21.11 {
/*import net.minecraft.world.attribute.EnvironmentAttributes;
*///?}
//? if >=26.3 {
/*import org.joml.Vector3fc;
*///?}

import java.util.List;

/**
 * {@link CgWorldQuery} over the client's level, 1.13.2 to 26.3: what each block and column is, read where each version
 * keeps it. Client only: constructed by {@code PlatformServiceModern.gl()}, beside the cursor. Render thread; reuses one
 * mutable position, so it allocates nothing but {@link #collisionBoxes}'s box list.
 *
 * <ul>
 *   <li>1.13.2 has no sound type, light listener, fluid height or averaged biome colours under these names: it answers
 *       {@code SURFACE_OTHER}, block brightness, a source block's height, and grass and foliage at the block alone
 *       (water 0).</li>
 *   <li>Fog, sky and water-fog colours come from the biome on 1.16.5 to 1.21.10 and from environment attributes from
 *       1.21.11; the tint from the block's tint source and the sprite from the block-state model set on 26.x.</li>
 * </ul>
 */
public final class WorldQueryModern implements CgWorldQuery {

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private Level seen;
    private int epoch, epochs;

    private static Level level() {
        return Minecraft.getInstance().level;
    }

    private BlockState state(Level level, int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z));
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
        VoxelShape shape = state(level, x, y, z).getCollisionShape(level, pos);
        if (shape.isEmpty()) return 0;
        List<AABB> boxes = shape.toAabbs();
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
    }

    @Override
    public float fluidHeight(int x, int y, int z) {
        Level level = level();
        if (level == null) return Float.NaN;
        FluidState fluid = level.getFluidState(pos.set(x, y, z));
        if (fluid.isEmpty()) return Float.NaN;
        //? if >=1.14 {
        return fluid.getHeight(level, pos);
        //?} else {
        /*return 8f / 9f;
        *///?}
    }

    @Override
    public int fluidKind(int x, int y, int z) {
        Level level = level();
        if (level == null) return FLUID_NONE;
        FluidState fluid = level.getFluidState(pos.set(x, y, z));
        if (fluid.isEmpty()) return FLUID_NONE;
        if (fluid.getType().isSame(Fluids.WATER)) return FLUID_WATER;
        return fluid.getType().isSame(Fluids.LAVA) ? FLUID_LAVA : FLUID_NONE;
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
        Level level = level();
        if (level == null) return 0;
        BlockState state = state(level, x, y, z);
        //? if >=26.1 {
        /*BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
        if (source == null) return 0;
        int c = source.colorInWorld(state, Minecraft.getInstance().level, pos);
        return c == -1 ? 0 : 0xFF000000 | c;
        *///?} elif >=1.14 {
        int c = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        return c == -1 ? 0 : 0xFF000000 | c;
        //?} else {
        /*int c = Minecraft.getInstance().getBlockColors().getColor(state, level, pos);
        return c == -1 ? 0 : 0xFF000000 | c;
        *///?}
    }

    @Override
    public int biomeColor(int x, int y, int z, int kind) {
        Level level = level();
        if (level == null) return 0;
        pos.set(x, y, z);
        // Minecraft's own level field: on 26.x only the client level is a tint getter.
        //? if >=1.14 {
        switch (kind) {
            case BIOME_GRASS: return 0xFF000000 | BiomeColors.getAverageGrassColor(Minecraft.getInstance().level, pos);
            case BIOME_FOLIAGE: return 0xFF000000 | BiomeColors.getAverageFoliageColor(Minecraft.getInstance().level, pos);
            case BIOME_WATER: return 0xFF000000 | BiomeColors.getAverageWaterColor(Minecraft.getInstance().level, pos);
            default: break;
        }
        //?} else {
        /*// 1.13's averaging helpers are unmapped: the biome's own colour at the block.
        switch (kind) {
            case BIOME_GRASS: return 0xFF000000 | level.getBiome(pos).getGrassColor(pos);
            case BIOME_FOLIAGE: return 0xFF000000 | level.getBiome(pos).getFoliageColor(pos);
            default: break;
        }
        *///?}
        //? if >=26.3 {
        /*switch (kind) {
            case BIOME_FOG: return argb(level.environmentAttributes().getValue(EnvironmentAttributes.FOG_COLOR, pos));
            case BIOME_SKY: return argb(level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR, pos));
            case BIOME_WATER_FOG: return argb(level.environmentAttributes().getValue(EnvironmentAttributes.WATER_FOG_COLOR, pos));
            default: break;
        }
        *///?} elif >=1.21.11 {
        /*switch (kind) {
            case BIOME_FOG: return 0xFF000000 | level.environmentAttributes().getValue(EnvironmentAttributes.FOG_COLOR, pos);
            case BIOME_SKY: return 0xFF000000 | level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR, pos);
            case BIOME_WATER_FOG:
                return 0xFF000000 | level.environmentAttributes().getValue(EnvironmentAttributes.WATER_FOG_COLOR, pos);
            default: break;
        }
        *///?} elif >=1.16.5 {
        Biome biome = biome(level);
        switch (kind) {
            case BIOME_FOG: return 0xFF000000 | biome.getFogColor();
            case BIOME_SKY: return 0xFF000000 | biome.getSkyColor();
            case BIOME_WATER_FOG: return 0xFF000000 | biome.getWaterFogColor();
            default: break;
        }
        //?}
        return 0;
    }

    //? if >=26.3 {
    /*// An RGB colour, 0 to 1 a channel, as ARGB with alpha 255: 26.3's colour attributes.
    private static int argb(Vector3fc c) {
        return 0xFF000000 | Math.round(c.x() * 255f) << 16 | Math.round(c.y() * 255f) << 8 | Math.round(c.z() * 255f);
    }
    *///?}

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
    private Biome biome(Level level) {
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
    public boolean spriteRect(int x, int y, int z, float[] out) {
        Level level = level();
        if (level == null) return false;
        BlockState state = state(level, x, y, z);
        if (state.isAir()) return false;
        //? if >=26.1 {
        /*TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet()
                .getParticleMaterial(state).sprite();
        *///?} else {
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
        //?}
        if (sprite == null) return false;
        out[0] = sprite.getU0();
        out[1] = sprite.getV0();
        out[2] = sprite.getU1();
        out[3] = sprite.getV1();
        return true;
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
            epoch = level == null ? 0 : ++epochs;
        }
        return epoch;
    }
}
