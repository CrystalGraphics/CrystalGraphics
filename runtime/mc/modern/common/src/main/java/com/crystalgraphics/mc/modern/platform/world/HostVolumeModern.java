package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.audio.CgHostVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

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
        SoundSource source;
        switch (category) {
            case PLAYERS: source = SoundSource.PLAYERS; break;
            case VOICE: source = SoundSource.VOICE; break;
            case MUSIC: source = SoundSource.MUSIC; break;
            case AMBIENT: source = SoundSource.AMBIENT; break;
            case BLOCKS: source = SoundSource.BLOCKS; break;
            default:
                //? if >=1.21.6 {
                /*source = SoundSource.UI;
                *///?} else {
                return 1f;
                //?}
        }
        return Minecraft.getInstance().options.getSoundSourceVolume(source);
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
