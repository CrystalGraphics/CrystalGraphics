package com.crystalgraphics.mc.legacy.platform.world;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeColorHelper;
//? if >=1.9 {
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
//?} else {
/*import net.minecraft.util.BlockPos;
import net.minecraft.world.biome.BiomeGenBase;
*///?}

/**
 * {@link LevelWorldQueryLegacy} over the client's level, with what only a client has: the tint, biome colours and the
 * particle sprite. Client only: provided by {@code PlatformServiceLegacy.register}. Render thread.
 *
 * <ul>
 *   <li>No per-biome fog or water-fog colour exists before 1.13: those answer 0. The sky colour is the biome's
 *       temperature colour.</li>
 * </ul>
 */
public final class WorldQueryLegacy extends LevelWorldQueryLegacy {

    public WorldQueryLegacy() {
        super(null);
    }

    @Override
    World world() {
        return ClientLegacy.world();
    }

    @Override
    public int tint(int x, int y, int z) {
        World world = world();
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
        World world = world();
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

    @Override
    public boolean spriteRect(int x, int y, int z, float[] out) {
        World world = world();
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
}
