package com.crystalgraphics.vfx.net;

import com.crystalgraphics.collision.CgSentHit;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CgHit;
import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.vfx.CgVfxAuthority;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.look.CgVfxParam;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * An authoritative cue's server behaviour: its effect, run headless on the server tick, publishing into the cue what its
 * viewers draw and emitting what it strikes. Stopped, the cue is the layer's stopping object while the effect plays
 * out, so its blast is still confirmed to viewers; it ends when the effect dies, or at the type's linger.
 */
final class CgVfxCueAuthority implements CgNetBehaviour, Consumer<CgVfxParam> {

    private static final Logger LOGGER = LogManager.getLogger("CgVfx");
    /** Seconds of game time a server tick runs. */
    static final float SERVER_TICK = 1f / 20f;
    /** Cues whose authority tests no world, warned once each. */
    private static final Set<String> LEVELLESS = ConcurrentHashMap.newKeySet();

    private final CgVfxCue.Play play;
    private final CgVfxAuthority authority;
    private final CgSentHit sent = new CgSentHit();

    /** Builds {@code play}'s effect, as its registry entry makes it, against its level. */
    CgVfxCueAuthority(CgVfxCue.Play play) {
        CgVfxRegistry.Entry entry = CgVfxRegistry.get(play.id());
        if (entry == null) {
            throw new IllegalStateException("cue '" + play.id() + "' is authoritative, but the server has nothing to run:"
                    + " declare it with what it plays, CgVfxOwner.cue(path, look, factory), in common code");
        }
        this.play = play;
        CgVfxEffect effect;
        play.authoritative = true;
        try {
            effect = play.build(entry);
        } finally {
            play.authoritative = false;
        }
        CgLevelQueries levels = CgPlatform.get(CgLevelQueries.SERVICE);
        Object level = play.obj.level();
        if (levels.world(level) == CgWorldQuery.NONE && LEVELLESS.add(play.id())) {
            LOGGER.warn("[CgVfx] {}'s authority tests no world: at() was given a {}, neither a level nor an entity in one,"
                    + " so it strikes nothing", play.id(), play.level.getClass().getName());
        }
        authority = new CgVfxAuthority(effect, CgCollisionWorld.of(level));
        effect.onHit(this::hit);
    }

    CgVfxEffect effect() {
        return authority.effect();
    }

    /** The cue changed through its {@code Playing}: the effect takes it as a viewer's would. */
    void cueChanged() {
        CgVfxEffect effect = authority.effect();
        if (effect instanceof CgVfxCue.Live) ((CgVfxCue.Live) effect).cueChanged(play);
    }

    @Override
    public void tick(CgNetObject obj) {
        CgVfxEffect effect = authority.effect();
        try {
            authority.advance(SERVER_TICK);
        } catch (RuntimeException e) {
            LOGGER.error("[CgVfx] {} threw on the server, and is killed", play.id(), e);
            effect.kill();
        }
        if (!obj.isStopping() && effect.state() != CgVfxEffect.State.PLAYING) obj.despawn();   // it stopped itself
        if (obj.isStopping() || obj.isRemoved()) return;
        authority.drainSet(this);
        if (effect instanceof CgVfxCue.Authoritative) ((CgVfxCue.Authoritative) effect).publish(play);
    }

    /** A number or colour set on the effect: into its fields. */
    @Override
    public void accept(CgVfxParam param) {
        CgVfxCue.LookFields look = play.cue.look;
        CgNetField<Float>[] fields = look == null ? null : look.of(param);
        if (fields == null) return;
        CgVfxEffect effect = authority.effect();
        for (int k = 0; k < fields.length; k++) play.setFloat(fields[k], effect.get(param, k));
    }

    private void hit(CgHit h) {
        play.obj.emit(CgVfxCue.HIT, sent.set(h, authority.effect().look().schema().indexOf(h.part())));
    }

    @Override
    public void despawned(CgNetObject obj, boolean killed) {
        if (killed) authority.effect().kill();
        else authority.effect().stop();
    }

    @Override
    public boolean done() {
        return authority.done();
    }
}
