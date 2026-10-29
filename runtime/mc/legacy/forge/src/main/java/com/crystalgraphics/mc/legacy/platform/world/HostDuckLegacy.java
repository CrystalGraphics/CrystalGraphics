package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.audio.CgHostVolume;
import net.minecraft.client.Minecraft;
//? if >=1.9 {
import net.minecraft.util.SoundCategory;
//?} else {
/*import net.minecraft.client.audio.SoundCategory;
*///?}

import java.util.Arrays;

/**
 * {@link CgHostDuck} on Forge 1.8.9 to 1.12.2: a factor per sound category that {@code SoundVolumeHook} multiplies into
 * the volume Minecraft computes, the playing sounds then recomputed by {@code SoundHandler.setSoundLevel}, which writes
 * no setting. Client only: constructed by {@code PlatformServiceLegacy.register}.
 */
public final class HostDuckLegacy implements CgHostDuck {

    private static final float[] FACTOR = new float[SoundCategory.values().length];

    static {
        Arrays.fill(FACTOR, 1f);
    }

    /** What the hook multiplies {@code category}'s volume by. */
    public static float factor(SoundCategory category) {
        return category == null ? 1f : FACTOR[category.ordinal()];
    }

    @Override
    public void duck(CgHostVolume.Category category, float factor) {
        SoundCategory c = HostVolumeLegacy.host(category);
        if (c == null) return;
        float f = Math.max(FLOOR, Math.min(1f, factor));
        if (FACTOR[c.ordinal()] == f) return;
        FACTOR[c.ordinal()] = f;
        Minecraft mc = Minecraft.getMinecraft();
        mc.getSoundHandler().setSoundLevel(c, mc.gameSettings.getSoundLevel(c));
    }

    @Override
    public float factor(CgHostVolume.Category category) {
        return factor(HostVolumeLegacy.host(category));
    }
}
