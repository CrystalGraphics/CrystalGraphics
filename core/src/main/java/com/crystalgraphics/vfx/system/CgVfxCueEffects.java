package com.crystalgraphics.vfx.system;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Quaternionf;

import com.crystalgraphics.cue.CgCueDefinition;
import com.crystalgraphics.cue.CgCueDefinition.Effects;
import com.crystalgraphics.cue.CgCueDefinition.Particles;
import com.crystalgraphics.cue.CgCueDefinition.Placement;
import com.crystalgraphics.cue.CgCueDefinition.Slot;
import com.crystalgraphics.cue.CgCueDefinition.Write;
import com.crystalgraphics.cue.CgCueEffects;
import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.world.CgEntityAttachments;

/**
 * Plays a cue's systems on a client: the VFX engine's {@link CgCueEffects}. Each entry is placed as its
 * {@code placement} says on the cue's target, if its {@code spawn_condition} passes for this viewer; a looping play's
 * {@code writes} reach the user parameters of the systems it is playing.
 *
 * <pre>{@code
 * CgCueManager cues = world.add(new CgCueManager(CgVfxAssets.instance()));
 * cues.effects(new CgVfxCueEffects(vfx, cues));
 * }</pre>
 *
 * <ul>
 *   <li>Only {@code particles} play here; sounds, shakes, lenses, force feedback and decals are their own systems'.</li>
 *   <li>{@code attach_to_target} follows the target's socket where the target is an entity, else the target itself;
 *       {@code do_not_attach} spawns at the socket and stays.</li>
 * </ul>
 */
public final class CgVfxCueEffects implements CgCueEffects {

    private final CgVfxSystem vfx;
    private final CgCueManager cues;
    private final double[] point = new double[3];
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
        if (target != null && !attach) {
            if (placement.socket() == null || !target.point(placement.socket(), point)) target.place(point, null);
        }
        CgVfxSystemInstance<?> made = new CgVfxSystemInstance<>(p.definition(), point[0], point[1], point[2]);
        if (attach) {
            int socket = placement.socket() == null ? -1 : CgEntityAttachments.find(placement.socket());
            boolean keep = placement.rule() == Placement.Rule.KEEP_WORLD;
            if (target.entity() >= 0 && socket >= 0) {
                if (keep) made.followKeepingOffset(target.entity(), socket);
                else made.follow(target.entity(), socket);
            } else if (keep) {
                made.followKeepingOffset(target);
            } else {
                made.follow(target);
            }
        }
        if (placement.rotation() != null) made.orient(turn.set(placement.rotation()));
        return made;
    }

    /** One looping play: what it started, and its {@code looping} systems apart, to stop them at removal. */
    private final class Playing implements Lasting {
        private final List<CgVfxSystemInstance<?>> alive = new ArrayList<>(), loops = new ArrayList<>();
        private final CgCueDefinition cue;

        Playing(CgCueDefinition cue) {
            this.cue = cue;
        }

        @Override
        public void start(CgCueParameters at, float age) {
            play(cue.application(), at, age, alive);
            int from = alive.size();
            play(cue.looping(), at, age, alive);
            for (int i = from; i < alive.size(); i++) loops.add(alive.get(i));
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
        }

        @Override
        public void reset() {
            alive.clear();
            loops.clear();
        }
    }
}
