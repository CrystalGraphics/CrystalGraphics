package com.crystalgraphics.render.scene.prefab;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.world.CgAttachment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * What plays a prefab's effect nodes: a VFX system by asset id, following the node. The scene names no VFX type, so the
 * VFX engine fills {@link #HOST} as it installs, Unreal's Niagara component hanging under an actor's root.
 *
 * <pre>{@code
 * CgPlatform.provide(CgPrefabEffects.HOST, (effect, at) -> ...);   // the VFX engine's, at install
 * CgPrefabEffects.Played flame = lantern.effect("flame");            // a spawned prefab's node
 * flame.play();
 * }</pre>
 *
 * <ul>
 *   <li>Without a host (a dedicated server, a scene with no VFX) an effect node plays nothing, said once.</li>
 *   <li>The client world's thread.</li>
 * </ul>
 */
public final class CgPrefabEffects {

    private static final Logger LOGGER = LogManager.getLogger(CgPrefabEffects.class);

    /** Starts one play of a system by asset id, following {@code at}; answers what stops it. */
    @FunctionalInterface
    public interface Host {
        /** The play of {@code effect} following {@code at}, started now. */
        Stoppable play(String effect, CgAttachment at);

        Host NONE = (effect, at) -> {
            LOGGER.warn("no VFX engine plays prefab effect {}: it plays nothing", effect);
            return () -> { };
        };
    }

    /** A play a host started: stopped gracefully, what it has in flight finishing. */
    @FunctionalInterface
    public interface Stoppable {
        void stop();
    }

    /** The host every scene's prefabs play their effect nodes through. */
    public static final CgService<Host> HOST = CgService.of("crystalgraphics:prefab_effects", Host.NONE);

    private CgPrefabEffects() {
    }

    /** An effect node of a spawned prefab: started at the spawn where its node says so, else by {@link #play}. */
    public static final class Played {
        private final String effect;
        private final CgAttachment at;
        private Stoppable playing;

        Played(String effect, CgAttachment at) {
            this.effect = effect;
            this.at = at;
        }

        /** Starts it, where it is not playing already. */
        public Played play() {
            if (playing == null) playing = CgPlatform.get(HOST).play(effect, at);
            return this;
        }

        /** Stops it, what it has in flight finishing; {@link #play} starts it again. */
        public Played stop() {
            if (playing != null) playing.stop();
            playing = null;
            return this;
        }

        public boolean playing() {
            return playing != null;
        }

        /** The system it plays, by asset id. */
        public String effect() {
            return effect;
        }
    }
}
