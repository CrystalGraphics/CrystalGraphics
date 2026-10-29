package com.crystalgraphics.vfx.system;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Quaternionf;

import com.crystalgraphics.cue.CgCueDefinition;
import com.crystalgraphics.cue.CgCueDefinition.Effects;
import com.crystalgraphics.cue.CgCueDefinition.Lens;
import com.crystalgraphics.cue.CgCueDefinition.Particles;
import com.crystalgraphics.cue.CgCueDefinition.Placement;
import com.crystalgraphics.cue.CgCueDefinition.Slot;
import com.crystalgraphics.cue.CgCueDefinition.Write;
import com.crystalgraphics.cue.CgCueEffects;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostBlock;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.world.CgEntityAttachments;

/**
 * Plays a cue's systems and its lens on a client: the VFX engine's {@link CgCueEffects}. Each entry is placed as its
 * {@code placement} says on the cue's target, if its {@code spawn_condition} passes for this viewer; a looping play's
 * {@code writes} reach the user parameters of the systems it is playing.
 *
 * <pre>{@code
 * CgCueManager cues = world.add(new CgCueManager(CgVfxAssets.instance()));
 * cues.effects(new CgVfxCueEffects(vfx, cues));
 * }</pre>
 *
 * <ul>
 *   <li>{@code particles} and a looping block's {@code lens} play here. The lens is a post volume over the whole
 *       screen for the cue's life. In the world it plays where the viewer ({@link CgCueManager#viewer}) stands within
 *       its reach as it starts; otherwise only on the target's own machine ({@link CgCueManager#localControl}).
 *       Sounds, shakes, force feedback and decals are their own systems'.</li>
 *   <li>{@code attach_to_target} follows the target's socket where the target is an entity, else the target itself;
 *       {@code do_not_attach} spawns at the socket and stays.</li>
 * </ul>
 */
public final class CgVfxCueEffects implements CgCueEffects {

    private final CgVfxSystem vfx;
    private final CgCueManager cues;
    private final double[] point = new double[3];
    private final double[] viewer = new double[3];
    private final Quaternionf turn = new Quaternionf();

    public CgVfxCueEffects(CgVfxSystem vfx, CgCueManager cues) {
        this.vfx = vfx;
        this.cues = cues;
    }

    @Override
    public void burst(CgCueDefinition cue, Effects block, CgCueParameters at, float age) {
        play(block, at, age, null);
    }

    @Override
    public Lasting lasting(CgCueDefinition cue) {
        return new Playing(cue);
    }

    /** {@code lens}'s look over the whole screen, at full weight. */
    static CgPostVolume openLens(CgPostStack stack, Lens lens) {
        return stack.volume(0, CgPostBlock.settings(lens.post() == null ? Map.of() : lens.post()));
    }

    /**
     * Unreal's falloff of a world effect at {@code distance}: 1 where neither radius is set, falling linearly from
     * {@code inner} to {@code outer}, or a cliff at {@code inner} where {@code outer} is not past it.
     */
    static float falloff(double distance, float inner, float outer) {
        if (inner <= 0f && outer <= 0f) return 1f;
        if (inner < outer) return (float) (1.0 - Math.max(0.0, Math.min(1.0, (distance - inner) / (outer - inner))));
        return distance < inner ? 1f : 0f;
    }

    /** Whether {@code lens} plays on this machine as its cue starts: once, at full strength for its life, as Unreal's. */
    private boolean lensPlays(Lens lens, CgCueParameters at) {
        if (!lens.inWorld()) return cues.locallyControlled(at.target());
        if (!cues.viewerAt(viewer)) return false;
        double dx = viewer[0] - at.x(), dy = viewer[1] - at.y(), dz = viewer[2] - at.z();
        return falloff(Math.sqrt(dx * dx + dy * dy + dz * dz), lens.inner(), lens.outer()) > 0f;
    }

    /** Plays each of {@code block}'s systems that spawns here, into {@code into} when it is kept. */
    private void play(Effects block, CgCueParameters at, float age, @Nullable List<CgVfxSystemInstance<?>> into) {
        List<Particles> particles = block.particles();
        for (int i = 0; i < particles.size(); i++) {
            Particles p = particles.get(i);
            if (p.definition() == null || !cues.spawns(p.where().condition(), at, i)) continue;
            CgVfxSystemInstance<?> made = place(p, at);
            if (age > 0f) made.startAt(age);
            vfx.play(made);
            if (into != null) into.add(made);
        }
    }

    private CgVfxSystemInstance<?> place(Particles p, CgCueParameters at) {
        Placement placement = p.where().placement();
        CgAttachment target = at.target();
        point[0] = at.x();
        point[1] = at.y();
        point[2] = at.z();
        boolean attach = target != null && placement.attach() == Placement.Attach.ATTACH_TO_TARGET;
        boolean turned = false;
        if (target != null && !attach) {
            // FindSpawnTransform: the target's socket transform, its turn included
            turned = placement.socket() != null ? target.point(placement.socket(), point, turn) : target.place(point, turn);
            if (!turned && placement.socket() != null) target.place(point, null);
        }
        CgVfxSystemInstance<?> made = new CgVfxSystemInstance<>(p.definition(), point[0], point[1], point[2]);
        if (attach) {
            int socket = placement.socket() == null ? -1 : CgEntityAttachments.find(placement.socket());
            boolean keep = placement.rule() == Placement.Rule.KEEP_WORLD;
            // SpawnSystemAttached(TargetComponent, SocketName): the socket, whatever the target is
            CgAttachment parent = placement.socket() == null || socket >= 0 && target.entity() >= 0 ? target
                    : target.atSocket(placement.socket());
            if (target.entity() >= 0 && socket >= 0) {
                if (keep) made.followKeepingOffset(target.entity(), socket);
                else made.follow(target.entity(), socket);
            } else if (keep) {
                made.followKeepingOffset(parent);
            } else {
                made.follow(parent);
            }
        }
        if (placement.rotation() != null) made.orient(turn.set(placement.rotation()));
        else if (turned) made.orient(turn);
        return made;
    }

    /** One looping play: what it started, and its {@code looping} systems apart, to stop them at removal. */
    private final class Playing implements Lasting {
        private final List<CgVfxSystemInstance<?>> alive = new ArrayList<>(), loops = new ArrayList<>();
        private final CgCueDefinition cue;
        @Nullable
        private CgPostVolume lens;

        Playing(CgCueDefinition cue) {
            this.cue = cue;
        }

        @Override
        public void start(CgCueParameters at, float age) {
            play(cue.application(), at, age, alive);
            int from = alive.size();
            play(cue.looping(), at, age, alive);
            for (int i = from; i < alive.size(); i++) loops.add(alive.get(i));
            Lens l = cue.looping().lens();
            if (l != null && cues.spawns(l.where().condition(), at, 0) && lensPlays(l, at)) lens = openLens(CgPostStack.get(), l);
        }

        private void closeLens() {
            if (lens != null) lens.close();
            lens = null;
        }

        @Override
        public void write(Write write, float[] value) {
            List<Slot> slots = write.slots();
            for (int s = 0; s < slots.size(); s++) {
                Slot slot = slots.get(s);
                for (int i = 0; i < alive.size(); i++) {
                    CgVfxSystemInstance<?> e = alive.get(i);
                    if (e.definition() != slot.definition()) continue;
                    CgVfxParam param = slot.param();
                    if (param instanceof CgVfxParam.Scalar sc) e.set(sc, value[0]);
                    else if (param instanceof CgVfxParam.Vector v) e.set(v, value[0], value[1], value[2]);
                    else if (param instanceof CgVfxParam.Color c) e.set(c, value[0], value[1], value[2], value[3]);
                }
            }
        }

        @Override
        public void remove(CgCueParameters at) {
            for (int i = 0; i < loops.size(); i++) loops.get(i).stop();
            loops.clear();
            closeLens();
            play(cue.removal(), at, 0f, alive);
        }

        @Override
        public boolean finished() {
            for (int i = 0; i < alive.size(); i++) {
                if (alive.get(i).executionState() != CgVfxExecutionState.COMPLETE) return false;
            }
            return true;
        }

        @Override
        public void kill() {
            for (int i = 0; i < alive.size(); i++) alive.get(i).kill();
            closeLens();
        }

        @Override
        public void reset() {
            alive.clear();
            loops.clear();
            closeLens();
        }
    }
}
