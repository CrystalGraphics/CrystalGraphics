package com.crystalgraphics.mc.modern.platform.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
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

/**
 * {@link LevelWorldQueryModern} over the client's level, with what only a client has: the tint, biome colours and the
 * particle sprite. Client only: constructed by {@code PlatformServiceModern.gl()}, beside the cursor. Render thread.
 *
 * <ul>
 *   <li>1.13.2 has no averaged biome colours under these names: grass and foliage at the block alone (water 0).</li>
 *   <li>Fog, sky and water-fog colours come from the biome on 1.16.5 to 1.21.10 and from environment attributes from
 *       1.21.11; the tint from the block's tint source and the sprite from the block-state model set on 26.x.</li>
 * </ul>
 */
public final class WorldQueryModern extends LevelWorldQueryModern {

    public WorldQueryModern() {
        super(null);
    }

    @Override
    Level level() {
        return Minecraft.getInstance().level;
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
}
