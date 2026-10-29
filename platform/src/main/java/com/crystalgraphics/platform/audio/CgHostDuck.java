package com.crystalgraphics.platform.audio;

import com.crystalgraphics.platform.CgService;

/**
 * Lowers the host's own sounds of a category, and restores them: a cinematic's hush on the world Minecraft plays. The
 * host multiplies what it computes for the category by the factor, for sounds playing and sounds to come; the player's
 * sliders are never written. The engine drives it from its sound mixes ({@code CgSoundMix.duckHost}).
 *
 * <pre>{@code
 * CgHostDuck duck = CgPlatform.get(CgHostDuck.SERVICE);
 * duck.duck(CgHostVolume.Category.BLOCKS, 0.25f);   // the world's blocks at a quarter
 * duck.duck(CgHostVolume.Category.BLOCKS, 1f);      // restored
 * }</pre>
 *
 * <pre>{@code
 * CgPlatform.provide(CgHostDuck.SERVICE, new HostDuckModern());   // a host, on a client
 * }</pre>
 *
 * <ul>
 *   <li>A factor is clamped to [{@link #FLOOR}, 1]: a host may stop a sound whose volume reaches 0.</li>
 *   <li>A category the host has no sounds of is ignored. Render thread.</li>
 *   <li>{@link #NONE} ignores it all: the harness, a server.</li>
 * </ul>
 */
public interface CgHostDuck {

    /** The lowest factor: above 0, which 1.7.10 takes as a stop. */
    float FLOOR = 0.001f;

    void duck(CgHostVolume.Category category, float factor);

    /** The factor the host holds for {@code category} now; 1 where it holds none. */
    default float factor(CgHostVolume.Category category) {
        return 1f;
    }

    CgHostDuck NONE = (category, factor) -> { };

    CgService<CgHostDuck> SERVICE = CgService.of("crystalgraphics:host_duck", NONE);
}
