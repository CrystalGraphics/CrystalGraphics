package com.crystalgraphics.vfx.system;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Quaternionf;

import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.sequence.CgSequenceBinding;
import com.crystalgraphics.sequence.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceSpawned;
import com.crystalgraphics.sequence.CgSequenceSpawner;
import com.crystalgraphics.sequence.CgSequenceSystem;
import com.crystalgraphics.sequence.CgSequenceTrack;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.render.CgVfxSortGroup;
import com.crystalgraphics.world.CgAttachment;

/**
 * Makes a sequence's {@code system} spawnables: a Niagara component the sequence owns, which its {@code system} tracks
 * activate, deactivate, kill and age. Each activation after a deactivation plays a new instance, as Niagara's
 * {@code Activate(bReset)} restarts its component.
 *
 * <pre>{@code
 * CgVfxSystemSpawner.install(sequencer);            // once, on a client's sequencer: the client world's system
 * }</pre>
 *
 * <pre>{@code
 * spawnables:
 *   - { id: s1, name: charge, at: { locator: k1 }, system: novamod:nova_cannon/charge }
 * tracks:
 *   - { id: t2, kind: system, binding: { spawnable: s1 }, sections: [ { id: t2a, range: [0, 44], keys: [[0, activate], [42, deactivate]] } ] }
 * }</pre>
 *
 * <ul>
 *   <li>Every system one play spawns sorts as one group ({@link CgVfxSortGroup}), so their layers keep the order their
 *       files give them, wherever each stands.</li>
 *   <li>Its instance follows its place where the spawnable follows ({@code at}'s {@code follow}), else is placed and
 *       turned once as it activates.</li>
 *   <li>A desired age further than a sequence tick from the instance's own plays it again from that age
 *       ({@code startAt}): Niagara's reset and seek in {@code DesiredAge} mode.</li>
 *   <li>A track naming a {@code handle} drives that emitter alone: it waits, stopped, until its track activates it,
 *       and starts the system if it is not playing.</li>
 *   <li>Its system's asset loads at its first activation if it has not: load the sequence's bundle ahead.</li>
 * </ul>
 */
public final class CgVfxSystemSpawner implements CgSequenceSpawner {

    private final CgVfxSystem vfx;
    /** Each playing sequence's sort group, dropped once it stops. */
    private final Map<CgSequencePlayer, CgVfxSortGroup> groups = new IdentityHashMap<>();

    public CgVfxSystemSpawner(CgVfxSystem vfx) {
        this.vfx = vfx;
    }

    /** Registers one for the {@code system} kind on {@code sequencer}, playing into the client world's system. */
    public static void install(CgSequencer sequencer) {
        sequencer.spawner("system", new CgVfxSystemSpawner(CgVfx.world()));
    }

    @Override
    public CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        if (!(spawnable.tree().get("system") instanceof String id)) {
            throw new CgAssetException(player.definition().id() + ": spawnables." + spawnable.id() + ": names no system");
        }
        // The emitters a track drives apart wait for it: held as each instance plays.
        Set<String> held = new LinkedHashSet<>();
        int index = player.definition().spawnables().indexOf(spawnable);
        for (CgSequenceTrack t : player.definition().tracks()) {
            CgSequenceBinding b = t.binding();
            if (t.kind().equals("system") && t.handle() != null && b.subject() == CgSequenceBinding.Subject.SPAWNABLE
                    && b.index() == index) {
                held.add(t.handle());
            }
        }
        return new Spawned(vfx, CgVfxAssets.ref(CgParticleKinds.SYSTEM, id), at, spawnable.follow(),
                (float) player.definition().seconds(1), group(player), held);
    }

    /** The sort group every effect {@code player} spawns joins: its systems' and its prefabs'. */
    public CgVfxSortGroup group(CgSequencePlayer player) {
        groups.keySet().removeIf(CgSequencePlayer::stopped);
        return groups.computeIfAbsent(player, p -> new CgVfxSortGroup());
    }

    /** One spawnable's component: the instance it plays now, if any. */
    private static final class Spawned implements CgSequenceSystem {
        private final CgVfxSystem vfx;
        private final CgAssetRef<CgVfxSystemDefinition> ref;
        private final CgAttachment at;
        private final CgVfxSortGroup group;
        private final boolean follow;
        /** A sequence tick in seconds: Niagara's seek delta. */
        private final float seekDelta;
        private final double[] point = new double[3];
        private final Quaternionf turn = new Quaternionf();
        private final Set<String> held;
        private final Map<String, Emitter> emitters = new HashMap<>();
        @Nullable
        private CgVfxSystemInstance<?> instance;

        Spawned(CgVfxSystem vfx, CgAssetRef<CgVfxSystemDefinition> ref, CgAttachment at, boolean follow, float seekDelta,
                CgVfxSortGroup group, Set<String> held) {
            this.vfx = vfx;
            this.held = held;
            this.group = group;
            this.ref = ref;
            this.at = at;
            this.follow = follow;
            this.seekDelta = seekDelta;
        }

        @Override
        public void activate(boolean reset) {
            if (active() && !reset) return;
            play(0f);
        }

        private void play(float age) {
            if (instance != null) instance.kill();
            if (!at.place(point, turn)) {
                point[0] = point[1] = point[2] = 0;
                turn.identity();
            }
            CgVfxSystemInstance<?> made = new CgVfxSystemInstance<>(ref, point[0], point[1], point[2]);
            if (follow) made.follow(at);
            else made.orient(turn);
            if (age > 0f) made.startAt(age);
            made.sortGroup(group);
            for (String h : held) {
                CgVfxEmitterInstance e = made.emitter(h);
                if (e != null) e.stop();
            }
            instance = vfx.play(made);
        }

        @Override
        @Nullable
        public CgSequenceSystem emitter(String handle) {
            if (!held.contains(handle)) return null;
            boolean has = false;
            for (CgVfxEmitter e : ref.get().emitters()) has |= e.name().equals(handle);
            return has ? emitters.computeIfAbsent(handle, Emitter::new) : null;
        }

        /** One emitter of its instance, which a track naming its handle drives. */
        private final class Emitter implements CgSequenceSystem {
            private final String handle;

            Emitter(String handle) {
                this.handle = handle;
            }

            @Nullable
            private CgVfxEmitterInstance running() {
                return Spawned.this.active() ? instance.emitter(handle) : null;
            }

            @Override
            public void activate(boolean reset) {
                if (active() && !reset) return;
                if (!Spawned.this.active()) play(0f);
                instance.emitter(handle).start(0f, 0f, 0f);
            }

            @Override
            public void deactivate() {
                CgVfxEmitterInstance e = running();
                if (e != null) e.stop();
            }

            @Override
            public void kill() {
                CgVfxEmitterInstance e = running();
                if (e != null) e.kill();
            }

            @Override
            public void detach() {
                Spawned.this.detach();
            }

            @Override
            public boolean active() {
                CgVfxEmitterInstance e = running();
                return e != null && e.spawning();
            }

            @Override
            public void age(float seconds) {
                throw new IllegalStateException(handle + ": a desired age on one emitter is not built yet");
            }

            @Override
            public void destroy(boolean graceful) {
                Spawned.this.destroy(graceful);
            }

            @Override
            public String toString() {
                return "emitter " + handle + " of " + Spawned.this;
            }
        }

        @Override
        public void deactivate() {
            if (instance != null) instance.stop();
        }

        @Override
        public void kill() {
            if (instance != null) instance.kill();
        }

        @Override
        public void detach() {
            if (instance == null || !at.place(point, turn)) return;
            double x = point[0], y = point[1], z = point[2];
            Quaternionf left = new Quaternionf(turn);
            instance.follow((p, q) -> {
                p[0] = x;
                p[1] = y;
                p[2] = z;
                if (q != null) q.set(left);
                return true;
            });
        }

        @Override
        public boolean active() {
            return instance != null && instance.executionState() == CgVfxExecutionState.ACTIVE;
        }

        @Override
        public void age(float seconds) {
            if (instance == null || Math.abs(instance.age() - seconds) <= seekDelta) return;
            play(seconds);
        }

        @Override
        public void destroy(boolean graceful) {
            if (instance == null) return;
            if (graceful) instance.stop();
            else instance.kill();
            instance = null;
        }

        @Override
        public String toString() {
            return "system " + ref;
        }
    }
}
