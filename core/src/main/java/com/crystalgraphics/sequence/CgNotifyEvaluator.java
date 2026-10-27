package com.crystalgraphics.sequence;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.sequence.time.CgSequenceEvaluationRange;

/**
 * The {@code notify} kind: its trigger sections fired as play crosses them, its spans begun, ticked and ended as time
 * enters, stays in and leaves them, as Unreal's notify queue dispatches {@code UAnimNotify} and
 * {@code UAnimNotifyState}. What a span sets on its kind's declared slots is blended every frame it lasts, so its end
 * restores them.
 */
final class CgNotifyEvaluator implements CgSequenceEvaluator {

    private static final int NOTIFY = 0, BEGIN = 1, TICK = 2, END = 3;
    /** {@code ZERO_ANIMWEIGHT_THRESH}: a notify fires where its sequence's weight is at least this. */
    private static final float DEFAULT_THRESHOLD = 0.00001f;
    private static final List<String> TIERS = List.of("low", "medium", "high", "ultra");

    private final CgSequencer sequencer;
    private final CgSequenceBlender blender;
    private final float[] none = new float[4];
    /** Queued calls, dispatched after the frame's evaluation. */
    private CgNotifyContext[] queued = new CgNotifyContext[16];
    private int[] queuedOps = new int[16];
    private float[] queuedDts = new float[16];
    private int queuedCount;

    CgNotifyEvaluator(CgSequencer sequencer, CgSequenceBlender blender) {
        this.sequencer = sequencer;
        this.blender = blender;
    }

    @Override
    public String kind() {
        return "notify";
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection unused) {
        CgSequenceTarget target = player.target(track);
        CgNotifyContext[] contexts = new CgNotifyContext[track.sections().size()];
        for (CgSequenceSection s : track.sections()) {
            CgNotifyKind kind = CgNotifyKind.get(String.valueOf(s.fields().get("kind")));
            String where = player.definition().id() + ": tracks." + track.id() + ".sections." + s.id();
            if (kind == null) throw new CgAssetException(where + ".kind: no notify kind " + s.fields().get("kind") + " is registered");
            int[] channels = new int[kind.writes().size()];
            for (int i = 0; i < channels.length; i++) {
                String slot = kind.writes().get(i);
                int index = target == null ? -1 : target.slot(slot);
                if (index < 0) throw new CgAssetException(where + ": " + kind.id() + " writes " + slot + ", which " + (target == null ? "an unbound track" : target) + " does not have");
                channels[i] = blender.channel(target, index);
            }
            contexts[s.index()] = new CgNotifyContext(player, track, s, target, channels);
        }
        player.data(track, null, contexts);
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection unused, float tick) {
        // Presentation: a dedicated server never runs a notify's behaviour.
        if (sequencer.server() || !(player.data(track, null) instanceof CgNotifyContext[] contexts)) return;
        CgSequenceEvaluationRange range = player.range();
        int keyFrom = range.keyFrom(), keyEnd = range.keyEnd(), now = (int) Math.floor(tick);
        double seconds = player.seconds();
        for (CgNotifyContext c : contexts) {
            CgSequenceSection s = c.section();
            if (!s.active() || !passesFilter(s)) continue;
            boolean branching = Boolean.TRUE.equals(s.fields().get("branching_point"));
            if (s.fields().get("at") instanceof Number at) {
                // A trigger: once, as play crosses it; a jump crosses nothing.
                int t = at.intValue();
                if (range.isSet() && !range.jumped() && t >= keyFrom && t < keyEnd && passesChance(player, c)) call(c, NOTIFY, 0f, branching);
                continue;
            }
            boolean inside = s.contains(now) && threshold(s) <= 1f;
            if (inside && !c.active) {
                c.active = true;
                c.lastSeconds = seconds;
                call(c, BEGIN, 0f, branching);
            } else if (inside) {
                call(c, TICK, (float) (seconds - c.lastSeconds), branching);
                c.lastSeconds = seconds;
            } else if (c.active) {
                c.active = false;
                call(c, END, 0f, branching);
                Arrays.fill(c.masks, 0);
            }
            if (c.active) {
                for (int i = 0; i < c.channels.length; i++) {
                    if (c.masks[i] != 0) blender.add(c.channels[i], c.values[i], c.masks[i], CgSequenceSection.Blend.ABSOLUTE, 1f, none, true);
                }
            }
        }
    }

    @Override
    public void postEvaluation() {
        for (int i = 0; i < queuedCount; i++) dispatch(queued[i], queuedOps[i], queuedDts[i]);
        Arrays.fill(queued, 0, queuedCount, null);
        queuedCount = 0;
    }

    @Override
    public void stopped(CgSequencePlayer player) {
        for (CgSequenceTrack track : player.definition().tracks()) {
            if (!track.kind().equals("notify") || !(player.data(track, null) instanceof CgNotifyContext[] contexts)) continue;
            for (CgNotifyContext c : contexts) {
                if (!c.active) continue;
                c.active = false;
                dispatch(c, END, 0f);
            }
        }
    }

    private void call(CgNotifyContext c, int op, float dt, boolean branching) {
        if (branching) {
            dispatch(c, op, dt);
            return;
        }
        if (queuedCount == queued.length) {
            queued = Arrays.copyOf(queued, queuedCount * 2);
            queuedOps = Arrays.copyOf(queuedOps, queuedCount * 2);
            queuedDts = Arrays.copyOf(queuedDts, queuedCount * 2);
        }
        queued[queuedCount] = c;
        queuedOps[queuedCount] = op;
        queuedDts[queuedCount++] = dt;
    }

    private static void dispatch(CgNotifyContext c, int op, float dt) {
        CgNotifyKind.Behaviour b = CgNotifyKind.get(String.valueOf(c.section().fields().get("kind"))).behaviour();
        switch (op) {
            case NOTIFY -> b.notify(c);
            case BEGIN -> b.begin(c);
            case TICK -> b.tick(c, dt);
            default -> b.end(c);
        }
    }

    /** {@code NotifyFilterType}: by our significance tier, the viewer's at least the section's. */
    private boolean passesFilter(CgSequenceSection s) {
        if (!(s.fields().get("filter") instanceof Map<?, ?> f) || !(f.get("significance") instanceof String tier)) return true;
        return sequencer.significance() >= TIERS.indexOf(tier);
    }

    private static float threshold(CgSequenceSection s) {
        return s.fields().get("weight_threshold") instanceof Number n ? n.floatValue() : DEFAULT_THRESHOLD;
    }

    /**
     * {@code PassesChanceOfTriggering}, a trigger's alone: drawn from the player's seed, the section and how often it
     * fired, so every viewer of one play agrees.
     */
    private static boolean passesChance(CgSequencePlayer player, CgNotifyContext c) {
        float chance = c.section().fields().get("chance") instanceof Number n ? n.floatValue() : 1f;
        if (chance >= 1f) return true;
        long h = player.seed() ^ (long) c.track().index() << 32 ^ (long) c.section().index() << 16 ^ c.fires++;
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h >>> 40) / (float) (1 << 24) < chance;
    }
}
