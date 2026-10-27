package com.crystalgraphics.sequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.crystalgraphics.api.material.CgParameterCollection;

/**
 * The blend phase: every section's value for a target slot, from every player, accumulated by blend type and weight,
 * then combined and written once. Unreal's piecewise blender systems with their pre-animated state: a slot's value
 * before any sequence animated it is captured as its first animator arrives, blended toward where weights fall
 * short, and put back when its last animator leaves if any of them restores state.
 */
final class CgSequenceBlender {

    /** One target slot: its accumulators this frame, its initial value, and who animated it. */
    private static final class Channel {
        final CgSequenceTarget target;
        final int slot;
        final float[] initial = new float[4];
        final float[] absolute = new float[4], relative = new float[4], additive = new float[4];
        final float[] absoluteWeight = new float[4], relativeWeight = new float[4], additiveWeight = new float[4];
        /** Overrides and, where there are any, additives too, in the order they were evaluated. */
        float[] ordered = new float[0];
        int orderedCount, mask, users, restorers, lastUsers, lastRestorers, lastMask;
        boolean hasOverride;

        Channel(CgSequenceTarget target, int slot) {
            this.target = target;
            this.slot = slot;
        }
    }

    private record Key(CgSequenceTarget target, int slot) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.target == target && k.slot == slot;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(target) * 31 + slot;
        }
    }

    private final Map<Key, Integer> ids = new HashMap<>();
    /** One target a collection, so every sequence keying it blends into the same channels. */
    private final Map<CgParameterCollection, CgSequenceGlobals> globals = new HashMap<>();
    private final List<Channel> channels = new ArrayList<>();
    private final float[] out = new float[4];

    /** {@code collection} as a target, the same one for every sequence. */
    CgSequenceTarget globals(CgParameterCollection collection) {
        return globals.computeIfAbsent(collection, CgSequenceGlobals::new);
    }

    /** The channel of {@code target}'s slot {@code slot}, made once: at bind. */
    int channel(CgSequenceTarget target, int slot) {
        Objects.requireNonNull(target);
        return ids.computeIfAbsent(new Key(target, slot), k -> {
            channels.add(new Channel(target, slot));
            return channels.size() - 1;
        });
    }

    /**
     * One section's value for channel {@code id} this frame: its components in {@code mask}, how it blends, its weight,
     * its base (for {@code additive_from_base}), and whether it restores state when it ends.
     */
    void add(int id, float[] values, int mask, CgSequenceSection.Blend blend, float weight, float[] base, boolean restores) {
        Channel c = channels.get(id);
        if (c.users == 0 && c.lastUsers == 0) {
            // Its first animator: what it was before any sequence touched it.
            c.target.get(c.slot, c.initial);
        }
        c.users++;
        if (restores) c.restorers++;
        c.mask |= mask;
        switch (blend == null ? CgSequenceSection.Blend.ABSOLUTE : blend) {
            case ABSOLUTE -> accumulate(c.absolute, c.absoluteWeight, values, mask, weight);
            case RELATIVE -> accumulate(c.relative, c.relativeWeight, values, mask, weight);
            case ADDITIVE_FROM_BASE -> {
                for (int i = 0; i < 4; i++) {
                    if ((mask & 1 << i) == 0) continue;
                    c.additive[i] += (values[i] - base[i]) * weight;
                    c.additiveWeight[i] += weight;
                }
            }
            case ADDITIVE, OVERRIDE -> order(c, blend == CgSequenceSection.Blend.OVERRIDE, values, mask, weight);
        }
    }

    private static void accumulate(float[] total, float[] weights, float[] values, int mask, float weight) {
        for (int i = 0; i < 4; i++) {
            if ((mask & 1 << i) == 0) continue;
            total[i] += values[i] * weight;
            weights[i] += weight;
        }
    }

    /** An override, or an additive: kept in order, since an override replaces what came before it. */
    private static void order(Channel c, boolean override, float[] values, int mask, float weight) {
        c.hasOverride |= override;
        int at = c.orderedCount * 7;
        if (c.ordered.length < at + 7) c.ordered = Arrays.copyOf(c.ordered, Math.max(14, c.ordered.length * 2));
        c.ordered[at] = override ? 1f : 0f;
        c.ordered[at + 1] = mask;
        c.ordered[at + 2] = weight;
        System.arraycopy(values, 0, c.ordered, at + 3, 4);
        c.orderedCount++;
    }

    /** Combines and writes every channel animated this frame; restores those whose last animators left. */
    void apply() {
        for (int id = 0; id < channels.size(); id++) {
            Channel c = channels.get(id);
            if (c.users > 0) {
                for (int i = 0; i < 4; i++) {
                    if ((c.mask & 1 << i) != 0) out[i] = combine(c, i);
                }
                // Additives with no override among them add on top.
                if (!c.hasOverride) {
                    for (int k = 0; k < c.orderedCount; k++) addOrdered(c, k);
                } else {
                    for (int k = 0; k < c.orderedCount; k++) {
                        if (c.ordered[k * 7] == 0f) addOrdered(c, k);
                        else overrideOrdered(c, k);
                    }
                }
                c.target.set(c.slot, out, c.mask);
            } else if (c.lastUsers > 0 && c.lastRestorers > 0) {
                c.target.set(c.slot, c.initial, c.lastMask);
            }
            c.lastUsers = c.users;
            c.lastRestorers = c.restorers;
            c.lastMask = c.users > 0 ? c.mask : 0;
            reset(c);
        }
    }

    /** {@code BlendResultsWithInitial}, for one component, before ordered additives and overrides. */
    private static float combine(Channel c, int i) {
        float initial = c.initial[i];
        float absW = c.absoluteWeight[i], relW = c.relativeWeight[i];
        float rel = c.relative[i] + (relW != 0f ? initial * relW : 0f);
        float total = absW + relW;
        if (total != 0f) {
            // A partial absolute weight ramps from the initial value, which then counts toward the total.
            boolean partial = absW > 0f && absW < 1f;
            float abs = partial ? initial * (1f - absW) + c.absolute[i] : c.absolute[i];
            float weight = partial ? total + (1f - absW) : total;
            return (abs + rel) / weight + c.additive[i];
        }
        return c.additiveWeight[i] != 0f ? initial + c.additive[i] : initial;
    }

    private void addOrdered(Channel c, int k) {
        int at = k * 7, mask = (int) c.ordered[at + 1];
        float w = c.ordered[at + 2];
        for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) out[i] += c.ordered[at + 3 + i] * w;
    }

    private void overrideOrdered(Channel c, int k) {
        int at = k * 7, mask = (int) c.ordered[at + 1];
        float w = c.ordered[at + 2];
        for (int i = 0; i < 4; i++) {
            if ((mask & 1 << i) == 0) continue;
            float v = c.ordered[at + 3 + i];
            out[i] = w >= 1f ? v : out[i] * (1f - w) + v * w;
        }
    }

    private static void reset(Channel c) {
        Arrays.fill(c.absolute, 0f);
        Arrays.fill(c.relative, 0f);
        Arrays.fill(c.additive, 0f);
        Arrays.fill(c.absoluteWeight, 0f);
        Arrays.fill(c.relativeWeight, 0f);
        Arrays.fill(c.additiveWeight, 0f);
        c.orderedCount = 0;
        c.mask = 0;
        c.users = 0;
        c.restorers = 0;
        c.hasOverride = false;
    }
}
