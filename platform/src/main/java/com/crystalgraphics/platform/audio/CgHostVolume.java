package com.crystalgraphics.platform.audio;

import com.crystalgraphics.platform.CgService;

/**
 * The host's own volume sliders, 0 to 1: what the engine's sound follows, so a player's settings rule our sound as they
 * rule the host's. Client only; read on the render thread.
 *
 * <pre>{@code
 * CgHostVolume volume = CgPlatform.get(CgHostVolume.SERVICE);
 * float gain = volume.master() * volume.category(CgHostVolume.Category.PLAYERS);
 * }</pre>
 *
 * <pre>{@code
 * CgPlatform.provide(CgHostVolume.SERVICE, new HostVolumeModern());   // a host, on a client
 * }</pre>
 *
 * <ul>
 *   <li>{@link #category} is the category's own slider, master not included.</li>
 *   <li>A category the host has no slider for answers 1.</li>
 *   <li>{@link #NONE} answers 1 for all: full volume where no host has sliders.</li>
 * </ul>
 */
public interface CgHostVolume {

    /** The sliders a host may have, by what they hold. */
    enum Category { PLAYERS, VOICE, MUSIC, AMBIENT, BLOCKS, UI, HOSTILE, NEUTRAL, WEATHER, RECORDS }

    float master();

    float category(Category category);

    CgHostVolume NONE = new CgHostVolume() {
        @Override
        public float master() {
            return 1f;
        }

        @Override
        public float category(Category category) {
            return 1f;
        }
    };

    CgService<CgHostVolume> SERVICE = CgService.of("crystalgraphics:host_volume", NONE);
}
