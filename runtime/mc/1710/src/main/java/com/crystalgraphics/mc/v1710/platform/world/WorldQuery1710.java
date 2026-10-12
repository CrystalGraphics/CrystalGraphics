package com.crystalgraphics.mc.v1710.platform.world;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

/**
 * {@link LevelWorldQuery1710} over the client's level, with what only a client has: the tint, biome colours and the
 * particle sprite. Client only: provided by {@code PlatformService1710.onPreInit}. Render thread.
 *
 * <ul>
 *   <li>No per-biome fog or water-fog colour exists: those answer 0. The sky colour is the biome's temperature colour;
 *       the water colour is the biome's water multiplier.</li>
 *   <li>The sprite is the block's bottom face, the icon its breaking particles use.</li>
 * </ul>
 */
public final class WorldQuery1710 extends LevelWorldQuery1710 {

    public WorldQuery1710() {
        super(null);
    }

    @Override
    World world() {
        return Minecraft.getMinecraft().theWorld;
    }

    @Override
    public int tint(int x, int y, int z) {
        World world = world();
        if (world == null) return 0;
        Block block = world.getBlock(x, y, z);
        if (block.getMaterial() == Material.air) return 0;
        // An untinted block answers white here.
        int c = block.colorMultiplier(world, x, y, z);
        return c == 0xFFFFFF ? 0 : 0xFF000000 | c;
    }

    @Override
    public int biomeColor(int x, int y, int z, int kind) {
        World world = world();
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

    @Override
    public boolean spriteRect(int x, int y, int z, float[] out) {
        World world = world();
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
}
