package com.crystalgraphics.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A set of adjustments to sound classes, faded in while it is pushed and out once popped: Unreal's {@code USoundMix}
 * with its {@code FSoundClassAdjuster}s. A cinematic's hush is one.
 *
 * <pre>{@code
 * CgSoundMix hush = new CgSoundMix(0.5f, 1.0f)                  // half a second in, a second out
 *         .adjust(CgSoundClass.AMBIENT, 0.2f, 1f)              // its volume and pitch multipliers
 *         .adjust(CgSoundClass.MUSIC, 0.4f, 1f);
 * audio.push(hush);
 * audio.pop(hush);
 * }</pre>
 *
 * <ul>
 *   <li>An adjustment reaches the class's children too, as {@code bApplyToChildren}.</li>
 *   <li>Mixes pushed together multiply.</li>
 *   <li>Set up before it is pushed: adjusting a pushed mix is not supported.</li>
 * </ul>
 */
public final class CgSoundMix {

    record Adjuster(CgSoundClass soundClass, float volume, float pitch) {
    }

    private final float fadeIn, fadeOut;
    private final List<Adjuster> adjusters = new ArrayList<>();

    /** Fading in over {@code fadeIn} seconds as it is pushed, out over {@code fadeOut} as it is popped. */
    public CgSoundMix(float fadeIn, float fadeOut) {
        this.fadeIn = fadeIn;
        this.fadeOut = fadeOut;
    }

    public CgSoundMix adjust(CgSoundClass soundClass, float volume, float pitch) {
        adjusters.add(new Adjuster(Objects.requireNonNull(soundClass, "soundClass"), volume, pitch));
        return this;
    }

    float fadeIn() {
        return fadeIn;
    }

    float fadeOut() {
        return fadeOut;
    }

    List<Adjuster> adjusters() {
        return adjusters;
    }
}
