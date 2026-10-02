package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * Plays a sound in the host's world at a point, on this client: the boom of a blast, the crackle of a beam. The host
 * mixes it as its own world sounds, with distance and direction.
 *
 * <pre>{@code
 * CgPlatform.get(CgWorldSound.SERVICE).play("minecraft:entity.generic.explode", x, y, z, 4f, 0.8f);
 *
 * // a host, once, on a client only
 * CgPlatform.provide(CgWorldSound.SERVICE, new WorldSoundModern());
 * }</pre>
 *
 * <ul>
 *   <li>The sound is a resource location: {@code namespace:path}, as the host's sound registry names it. An unknown one
 *       plays nothing.</li>
 *   <li>Absolute world coordinates; volume 1 is heard about 16 blocks off, as in Minecraft, and pitch 1 is as recorded.</li>
 *   <li>{@link #NONE} plays nothing: the harness, a dedicated server. Render thread only.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgWorldSound {

    CgWorldSound NONE = (sound, x, y, z, volume, pitch) -> { };

    CgService<CgWorldSound> SERVICE = CgService.of("crystalgraphics:world_sound", NONE);

    void play(String sound, double x, double y, double z, float volume, float pitch);
}
