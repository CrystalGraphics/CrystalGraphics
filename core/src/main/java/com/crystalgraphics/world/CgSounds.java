package com.crystalgraphics.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgWorldSound;

/**
 * Sounds at a point in the host's world, on this client, mixed with distance and direction as its own. The only reader
 * of {@link CgWorldSound}.
 *
 * <pre>{@code
 * CgSounds.play("minecraft:entity.generic.explode", x, y, z, 4f, 0.8f);
 * }</pre>
 *
 * <ul>
 *   <li>A resource location as the host's sound registry names it; an unknown one plays nothing.</li>
 *   <li>Volume 1 is heard about 16 blocks off; pitch 1 is as recorded. Render thread; nothing plays on a server.</li>
 * </ul>
 */
public final class CgSounds {

    private CgSounds() {
    }

    public static void play(String sound, double x, double y, double z, float volume, float pitch) {
        CgPlatform.get(CgWorldSound.SERVICE).play(sound, x, y, z, volume, pitch);
    }
}
