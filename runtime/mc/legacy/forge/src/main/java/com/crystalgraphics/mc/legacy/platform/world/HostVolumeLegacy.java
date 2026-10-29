package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.audio.CgHostVolume;
import net.minecraft.client.Minecraft;
//? if >=1.9 {
import net.minecraft.util.SoundCategory;
//?} else {
/*import net.minecraft.client.audio.SoundCategory;
*///?}

/**
 * {@link CgHostVolume} from Minecraft's sound options on Forge 1.8.9 to 1.12.2; voice from 1.10.2. Client only:
 * constructed by {@code PlatformServiceLegacy.register}.
 */
public final class HostVolumeLegacy implements CgHostVolume {

    @Override
    public float master() {
        return Minecraft.getMinecraft().gameSettings.getSoundLevel(SoundCategory.MASTER);
    }

    @Override
    public float category(Category category) {
        SoundCategory c = host(category);
        return c == null ? 1f : Minecraft.getMinecraft().gameSettings.getSoundLevel(c);
    }

    /** {@code category}'s sound category, or null where this version has none. */
    public static SoundCategory host(Category category) {
        switch (category) {
            case PLAYERS: return SoundCategory.PLAYERS;
            case MUSIC: return SoundCategory.MUSIC;
            case AMBIENT: return SoundCategory.AMBIENT;
            case BLOCKS: return SoundCategory.BLOCKS;
            case WEATHER: return SoundCategory.WEATHER;
            case RECORDS: return SoundCategory.RECORDS;
            //? if >=1.9 {
            case VOICE: return SoundCategory.VOICE;
            case HOSTILE: return SoundCategory.HOSTILE;
            case NEUTRAL: return SoundCategory.NEUTRAL;
            //?} else {
            /*case HOSTILE: return SoundCategory.MOBS;
            case NEUTRAL: return SoundCategory.ANIMALS;
            *///?}
            default: return null;
        }
    }
}
