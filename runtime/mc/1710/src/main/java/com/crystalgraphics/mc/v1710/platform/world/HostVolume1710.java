package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.audio.CgHostVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;

/** {@link CgHostVolume} from Minecraft 1.7.10's sound options: no voice or UI slider. Client only. */
public final class HostVolume1710 implements CgHostVolume {

    @Override
    public float master() {
        return Minecraft.getMinecraft().gameSettings.getSoundLevel(SoundCategory.MASTER);
    }

    @Override
    public float category(Category category) {
        SoundCategory c = host(category);
        return c == null ? 1f : Minecraft.getMinecraft().gameSettings.getSoundLevel(c);
    }

    /** {@code category}'s sound category, or null where 1.7.10 has none (voice, UI). */
    public static SoundCategory host(Category category) {
        switch (category) {
            case PLAYERS: return SoundCategory.PLAYERS;
            case MUSIC: return SoundCategory.MUSIC;
            case AMBIENT: return SoundCategory.AMBIENT;
            case BLOCKS: return SoundCategory.BLOCKS;
            case WEATHER: return SoundCategory.WEATHER;
            case RECORDS: return SoundCategory.RECORDS;
            case HOSTILE: return SoundCategory.MOBS;
            case NEUTRAL: return SoundCategory.ANIMALS;
            default: return null;
        }
    }
}
