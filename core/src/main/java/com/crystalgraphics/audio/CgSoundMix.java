package com.crystalgraphics.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.crystalgraphics.platform.audio.CgHostVolume;

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
 * A cinematic hushes Minecraft's own sounds too, by their host category, faded with the rest:
 *
 * <pre>{@code
 * CgSoundMix cinematic = new CgSoundMix(0.3f, 1f)
 *         .duckHost(CgHostVolume.Category.BLOCKS, 0.2f)
 *         .duckHost(CgHostVolume.Category.HOSTILE, 0.2f)
 *         .duckHost(CgHostVolume.Category.WEATHER, 0.3f);
 * }</pre>
 *
 * <ul>
 *   <li>An adjustment reaches the class's children too, as {@code bApplyToChildren}.</li>
 *   <li>Mixes pushed together multiply.</li>
 *   <li>Set up before it is pushed: adjusting a pushed mix is not supported.</li>
 *   <li>{@link #duckHost} reaches the host's sounds only, never its sliders: the host is restored when the mix pops,
 *       and when the engine closes.</li>
 * </ul>
 */
public final class CgSoundMix {

    record Adjuster(CgSoundClass soundClass, float volume, float pitch) {
    }

    record HostDuck(CgHostVolume.Category category, float volume) {
    }

    private final float fadeIn, fadeOut;
    private final List<Adjuster> adjusters = new ArrayList<>();
    private final List<HostDuck> hostDucks = new ArrayList<>();

    /** Fading in over {@code fadeIn} seconds as it is pushed, out over {@code fadeOut} as it is popped. */
    public CgSoundMix(float fadeIn, float fadeOut) {
        this.fadeIn = fadeIn;
        this.fadeOut = fadeOut;
    }

    public CgSoundMix adjust(CgSoundClass soundClass, float volume, float pitch) {
        adjusters.add(new Adjuster(Objects.requireNonNull(soundClass, "soundClass"), volume, pitch));
        return this;
    }

    /** Lowers the host's own sounds of {@code category} to {@code volume}, 0 to 1, while it is pushed. */
    public CgSoundMix duckHost(CgHostVolume.Category category, float volume) {
        hostDucks.add(new HostDuck(Objects.requireNonNull(category, "category"), volume));
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

    List<HostDuck> hostDucks() {
        return hostDucks;
    }
}
