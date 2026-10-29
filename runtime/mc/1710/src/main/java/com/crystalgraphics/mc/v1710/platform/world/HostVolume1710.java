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
        SoundCategory c;
        switch (category) {
            case PLAYERS: c = SoundCategory.PLAYERS; break;
            case MUSIC: c = SoundCategory.MUSIC; break;
            case AMBIENT: c = SoundCategory.AMBIENT; break;
            case BLOCKS: c = SoundCategory.BLOCKS; break;
            default: return 1f;
        }
        return Minecraft.getMinecraft().gameSettings.getSoundLevel(c);
    }
}
