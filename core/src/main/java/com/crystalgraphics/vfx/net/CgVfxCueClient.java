package com.crystalgraphics.vfx.net;

import com.crystalgraphics.net.CgReplicated;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's side of cues: what arrives is built from the registry and played under the owner its id names. The
 * engine installs it with the world's VFX ({@code CgVfx.install}); nothing else calls it.
 */
public final class CgVfxCueClient {

    private static final Logger LOGGER = LogManager.getLogger("CgVfx");
    /** Ids a play missed, warned once each. */
    private static final Set<String> MISSED = ConcurrentHashMap.newKeySet();

    private static boolean installed;

    private CgVfxCueClient() {
    }

    /** Hears cues from the server from now on. Once, on the client. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        CgVfxWire.ensure();
        CgVfxWire.play.onReceive(CgVfxCueClient::play);
        CgVfxWire.playing.onClient(new View());
        CgVfxWire.kept.onClient(new View());
    }

    /** Builds {@code p} and plays it under its namespace's owner; null where nothing is registered under its id. */
    static CgVfxEffect play(CgVfxCue.Play<?> p) {
        CgVfxEffect effect = build(p);
        return effect == null ? null : CgVfx.owner(p.id.substring(0, p.id.indexOf(':'))).play(effect);
    }

    private static CgVfxEffect build(CgVfxCue.Play<?> p) {
        CgVfxRegistry.Entry entry = CgVfxRegistry.get(p.id);
        if (entry == null) {
            if (MISSED.add(p.id)) LOGGER.warn("[CgVfx] a cue arrived for '{}', which nothing registers here", p.id);
            return null;
        }
        return CgVfxWire.build(p, () -> entry.spawn(p));
    }

    /** Lasting cues the client sees: started at their age as they come into view, stopped as they leave. */
    private static final class View implements CgReplicated.View<CgVfxCue.Play<?>> {
        private final Map<Long, CgVfxEffect> live = new HashMap<>();

        @Override
        public void added(long id, CgVfxCue.Play<?> p) {
            CgVfxEffect effect = play(p);
            if (effect != null) live.put(id, effect);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void changed(long id, CgVfxCue.Play<?> p) {
            CgVfxEffect effect = live.get(id);
            if (effect == null) return;
            if (effect.state() == CgVfxEffect.State.DEAD) {
                live.remove(id);
            } else if (p.killed) {
                live.remove(id);
                effect.kill();
            } else if (effect instanceof CgVfxCue.Live) {
                ((CgVfxCue.Live) effect).cueChanged(p);
            } else if (p.params instanceof CgVfxParams) {
                CgVfxWire.apply((CgVfxParams) p.params, effect);
            }
        }

        @Override
        public void removed(long id) {
            CgVfxEffect effect = live.remove(id);
            if (effect != null) effect.stop();
        }
    }

    /** Back to uninstalled, for a test that reset the network's declarations. */
    static synchronized void resetForTesting() {
        installed = false;
    }
}
