package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.audio.CgHostVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;

/**
 * {@link CgHostVolume} from Minecraft's sound options, 1.13.2 to 26.3. Client only: constructed by
 * {@code PlatformServiceModern.gl()}.
 */
public final class HostVolumeModern implements CgHostVolume {

    @Override
    public float master() {
        return Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
    }

    @Override
    public float category(Category category) {
        SoundSource source = source(category);
        return source == null ? 1f : Minecraft.getInstance().options.getSoundSourceVolume(source);
    }

    /** {@code category}'s sound source, or null where this version has none (UI before 1.21.6). */
    @Nullable
    public static SoundSource source(Category category) {
        switch (category) {
            case PLAYERS: return SoundSource.PLAYERS;
            case VOICE: return SoundSource.VOICE;
            case MUSIC: return SoundSource.MUSIC;
            case AMBIENT: return SoundSource.AMBIENT;
            case BLOCKS: return SoundSource.BLOCKS;
            case HOSTILE: return SoundSource.HOSTILE;
            case NEUTRAL: return SoundSource.NEUTRAL;
            case WEATHER: return SoundSource.WEATHER;
            case RECORDS: return SoundSource.RECORDS;
            default:
                //? if >=1.21.6 {
                /*return SoundSource.UI;
                *///?} else {
                return null;
                //?}
        }
    }

    /** The output device the player chose, or null for the system's default: 1.18.2 on. */
    public static String device() {
        //? if >=1.19.2 {
        return Minecraft.getInstance().options.soundDevice().get();
        //?} elif >=1.18.2 {
        /*return Minecraft.getInstance().options.soundDevice;
        *///?} else {
        /*return null;
        *///?}
    }
}
