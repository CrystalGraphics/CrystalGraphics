package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgWorldSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;

/**
 * {@link CgWorldSound} through Minecraft 1.7.10's sound handler: a positional sound, attenuated with distance. The id
 * is a sounds.json key of this version, {@code minecraft:random.explode}. Client only.
 */
public final class WorldSound1710 implements CgWorldSound {

    @Override
    public void play(String sound, double x, double y, double z, float volume, float pitch) {
        if (sound == null || sound.isEmpty()) return;
        Minecraft.getMinecraft().getSoundHandler().playSound(
                new PositionedSoundRecord(new ResourceLocation(sound), volume, pitch, (float) x, (float) y, (float) z));
    }
}
