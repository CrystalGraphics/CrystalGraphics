package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.audio.CgHostVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;

import java.util.Arrays;

/**
 * {@link CgHostDuck} on 1.7.10: a factor per sound category that {@code SoundVolumeHook} multiplies into the volume
 * Minecraft computes, the playing sounds then recomputed by {@code SoundHandler.setSoundLevel}, which writes no
 * setting. That recompute stops a sound whose volume reaches 0, so the factor never does. Client only.
 */
public final class HostDuck1710 implements CgHostDuck {

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
        SoundCategory c = HostVolume1710.host(category);
        if (c == null) return;
        float f = Math.max(FLOOR, Math.min(1f, factor));
        if (FACTOR[c.ordinal()] == f) return;
        FACTOR[c.ordinal()] = f;
        Minecraft mc = Minecraft.getMinecraft();
        mc.getSoundHandler().setSoundLevel(c, mc.gameSettings.getSoundLevel(c));
    }

    @Override
    public float factor(CgHostVolume.Category category) {
        return factor(HostVolume1710.host(category));
    }
}
