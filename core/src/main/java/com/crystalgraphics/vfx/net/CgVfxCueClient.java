package com.crystalgraphics.vfx.net;

import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.look.CgVfxEnding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's side of cues: each cue object the server sends is built from the registry and played under the owner
 * its id names. The engine installs it with the world's VFX ({@code CgVfx.install}); nothing else calls it.
 */
public final class CgVfxCueClient {

    private static final Logger LOGGER = LogManager.getLogger("CgVfx");
    /** Ids a play missed, warned once each. */
    private static final Set<String> MISSED = ConcurrentHashMap.newKeySet();

    private static volatile boolean installed;

    private CgVfxCueClient() {
    }

    /** Builds the cues the server sends from now on. Once, on the client. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        for (CgVfxCue cue : CgVfxCue.all()) view(cue);
    }

    /** A cue was declared: built here too, once installed. */
    static synchronized void declared(CgVfxCue cue) {
        if (installed) view(cue);
    }

    private static void view(CgVfxCue cue) {
        cue.type.onClient(obj -> new View(cue));
        cue.type.onEvent(CgVfxCue.TWEEN, (obj, tween) -> {
            View view = obj.behaviour();
            if (view != null && view.effect != null) CgVfxCue.play(cue.look, view.effect, tween);
        });
        cue.type.onEvent(CgVfxCue.END, (obj, name) -> {
            View view = obj.behaviour();
            CgVfxEnding ending = CgVfxEnding.named(name);
            if (view != null && view.effect != null && ending != null) view.effect.end(ending);
        });
    }

    /** Builds {@code p} and plays it under its namespace's owner; null where nothing is registered under its id. */
    @Nullable
    static CgVfxEffect play(CgVfxCue.Play p) {
        String id = p.id();
        CgVfxRegistry.Entry entry = CgVfxRegistry.get(id);
        if (entry == null) {
            if (MISSED.add(id)) LOGGER.warn("[CgVfx] a cue arrived for '{}', which nothing registers here", id);
            return null;
        }
        return CgVfx.owner(id.substring(0, id.indexOf(':'))).play(p.build(entry));
    }

    /** A cue object as this client shows it: its effect, started at its age, taking what changes. */
    static final class View implements CgNetBehaviour {
        private final CgVfxCue cue;
        private CgVfxCue.Play play;
        @Nullable
        CgVfxEffect effect;
        private boolean changed;

        View(CgVfxCue cue) {
            this.cue = cue;
        }

        @Override
        public void spawned(CgNetObject obj, float age) {
            play = CgVfxCue.Play.read(cue, obj);
            play.age = age * CgVfxCueAuthority.SERVER_TICK;
            effect = play(play);
        }

        @Override
        public void changed(CgNetObject obj, CgNetField<?> field) {
            if (effect != null && !cue.applyLook(play, effect, field)) changed = true;
        }

        @Override
        public void received(CgNetObject obj) {
            if (!changed || effect == null) return;
            changed = false;
            play.readFrom(obj);
            if (!obj.isStopping() && effect instanceof CgVfxCue.Live) ((CgVfxCue.Live) effect).cueChanged(play);
        }

        /** The server's effect plays out: this one too, while its confirmed collisions still arrive. */
        @Override
        public void stopping(CgNetObject obj) {
            if (effect != null) effect.stop();
        }

        @Override
        public void moved(CgNetObject obj) {
            if (effect == null) return;
            play.readFrom(obj);
            if (effect instanceof CgVfxCue.Live) ((CgVfxCue.Live) effect).cueChanged(play);
        }

        @Override
        public void despawned(CgNetObject obj, boolean killed) {
            // A one-shot plays out whatever the network says: a refused prediction included.
            if (effect == null || obj.isFired()) return;
            if (killed) effect.kill();
            else effect.stop();
        }
    }

    /** Back to uninstalled, for a test. */
    static synchronized void resetForTesting() {
        installed = false;
    }
}
