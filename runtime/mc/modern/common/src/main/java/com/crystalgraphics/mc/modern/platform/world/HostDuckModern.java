package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.audio.CgHostVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;

import java.util.Arrays;

/**
 * {@link CgHostDuck} on 1.13.2 to 26.3: a factor per sound source that each loader's {@code SoundVolumeHook} multiplies
 * into the volume Minecraft computes, then the playing sounds of that source recomputed. Client only: constructed by
 * {@code PlatformServiceModern}.
 */
public final class HostDuckModern implements CgHostDuck {

    private static final float[] FACTOR = new float[SoundSource.values().length];

    static {
        Arrays.fill(FACTOR, 1f);
    }

    /** What the hook multiplies {@code source}'s volume by. */
    public static float factor(@Nullable SoundSource source) {
        return source == null ? 1f : FACTOR[source.ordinal()];
    }

    @Override
    public void duck(CgHostVolume.Category category, float factor) {
        SoundSource source = HostVolumeModern.source(category);
        if (source == null) return;
        float f = Math.max(FLOOR, Math.min(1f, factor));
        if (FACTOR[source.ordinal()] == f) return;
        FACTOR[source.ordinal()] = f;
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        //? if >=1.21.11 {
        /*sounds.refreshCategoryVolume(source);
        *///?} elif >=1.21.10 {
        /*sounds.updateSourceVolume(source);
        *///?} else {
        sounds.updateSourceVolume(source, Minecraft.getInstance().options.getSoundSourceVolume(source));
        //?}
    }

    @Override
    public float factor(CgHostVolume.Category category) {
        return factor(HostVolumeModern.source(category));
    }
}
