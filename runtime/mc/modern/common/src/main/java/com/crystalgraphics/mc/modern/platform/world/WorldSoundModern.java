package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgWorldSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
//? if >=1.19 {
import net.minecraft.util.RandomSource;
//?}

/**
 * {@link CgWorldSound} through Minecraft's sound manager, 1.13.2 to 26.3: a positional sound in the blocks category,
 * attenuated with distance as Minecraft's own world sounds are. Client only: constructed by
 * {@code PlatformServiceModern.gl()}.
 */
public final class WorldSoundModern implements CgWorldSound {

    //? if >=1.19 {
    private static final RandomSource RANDOM = RandomSource.create();
    //?}

    @Override
    public void play(String sound, double x, double y, double z, float volume, float pitch) {
        ResourceLocation id = ResourceLocation.tryParse(sound);
        if (id == null) return;
        //? if >=1.19 {
        SoundInstance instance = new SimpleSoundInstance(id, SoundSource.BLOCKS, volume, pitch, RANDOM, false, 0,
                SoundInstance.Attenuation.LINEAR, x, y, z, false);
        //?} elif >=1.16.5 {
        /*SoundInstance instance = new SimpleSoundInstance(id, SoundSource.BLOCKS, volume, pitch, false, 0,
                SoundInstance.Attenuation.LINEAR, x, y, z, false);
        *///?} elif >=1.14 {
        /*SoundInstance instance = new SimpleSoundInstance(id, SoundSource.BLOCKS, volume, pitch, false, 0,
                SoundInstance.Attenuation.LINEAR, (float) x, (float) y, (float) z, false);
        *///?} else {
        /*SoundInstance instance = new SimpleSoundInstance(id, SoundSource.BLOCKS, volume, pitch, false, 0,
                SoundInstance.Attenuation.LINEAR, (float) x, (float) y, (float) z);
        *///?}
        Minecraft.getInstance().getSoundManager().play(instance);
    }
}
