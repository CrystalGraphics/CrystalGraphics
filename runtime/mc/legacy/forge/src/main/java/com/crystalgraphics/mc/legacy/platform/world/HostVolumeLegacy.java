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
        SoundCategory c;
        switch (category) {
            case PLAYERS: c = SoundCategory.PLAYERS; break;
            case MUSIC: c = SoundCategory.MUSIC; break;
            case AMBIENT: c = SoundCategory.AMBIENT; break;
            case BLOCKS: c = SoundCategory.BLOCKS; break;
            //? if >=1.9 {
            case VOICE: c = SoundCategory.VOICE; break;
            //?}
            default: return 1f;
        }
        return Minecraft.getMinecraft().gameSettings.getSoundLevel(c);
    }
}
