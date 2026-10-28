package com.crystalgraphics.render.post.volume;

import java.util.Map;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawner;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.world.CgAttachment;

/**
 * Makes a sequence's {@code post} spawnables: a volume on the post stack, as Unreal's spawnable post process volume, its
 * {@code weight} keyed by a property track ({@code BlendWeight}).
 *
 * <pre>{@code
 * CgPostVolumeSpawner.install(sequencer);           // once, on a client's sequencer
 * }</pre>
 *
 * <pre>{@code
 * spawnables:
 *   - { id: s4, name: flash, post: { bloom: 1.6, exposure: { flash: 1.5 } }, priority: 10, unbound: true }
 *   - { id: s5, name: haze, at: { locator: k1 }, post: { chromatic: 0.3 }, radius: 4, blend_radius: 2 }
 * }</pre>
 *
 * <ul>
 *   <li>An {@code unbound} volume applies everywhere; otherwise it stands where its {@code at} places it, full within
 *       {@code radius} and fading over {@code blend_radius}, following that place each frame.</li>
 *   <li>Its weight starts at 1, as Unreal's; a track keys it from there.</li>
 *   <li>The stack orders volumes by whole priorities: a fractional {@code priority} fails at play, naming it.</li>
 * </ul>
 */
public final class CgPostVolumeSpawner implements CgSequenceSpawner {

    private final CgPostStack stack;

    public CgPostVolumeSpawner(CgPostStack stack) {
        this.stack = stack;
    }

    /** Registers one for the {@code post} kind on {@code sequencer}, over the client's post stack. */
    public static void install(CgSequencer sequencer) {
        sequencer.spawner("post", new CgPostVolumeSpawner(CgPostStack.get()));
    }

    @Override
    public CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        Map<?, ?> tree = spawnable.tree();
        String path = player.definition().id() + ": spawnables." + spawnable.id();
        float priority = tree.get("priority") instanceof Number n ? n.floatValue() : 0f;
        if (priority != Math.rint(priority)) throw new CgAssetException(path + ".priority: " + priority + " is not a whole priority");
        CgPostVolume volume = stack.volume((int) priority, CgPostBlock.settings(tree.get("post") instanceof Map<?, ?> m ? m : Map.of()));
        boolean bound = !Boolean.TRUE.equals(tree.get("unbound"));
        if (bound) {
            CgUnits units = player.definition().units();
            volume.radius(length(units, tree.get("radius"))).blend(length(units, tree.get("blend_radius")));
        }
        double[] point = new double[3];
        if (bound && at.place(point, null)) volume.at(point[0], point[1], point[2]);
        return new Spawned(volume, bound && spawnable.follow() ? at : null);
    }

    private static float length(CgUnits units, Object v) {
        return v instanceof Number ? ((Number) units.toHost(v, CgQuantity.LENGTH)).floatValue() : 0f;
    }

    private static final class Spawned implements CgSequenceSpawned {
        private static final int WEIGHT = 0;
        private final CgPostVolume volume;
        private final CgAttachment at;
        private final double[] point = new double[3];
        private float weight = 1f;

        Spawned(CgPostVolume volume, CgAttachment at) {
            this.volume = volume;
            this.at = at;
        }

        @Override
        public int slot(String name) {
            return name.equals("weight") ? WEIGHT : -1;
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            if (slot == WEIGHT && (mask & 1) != 0) volume.weight(weight = values[0]);
        }

        @Override
        public void get(int slot, float[] out) {
            if (slot == WEIGHT) out[0] = weight;
        }

        @Override
        public void update() {
            if (at != null && at.place(point, null)) volume.at(point[0], point[1], point[2]);
        }

        @Override
        public void destroy(boolean graceful) {
            volume.close();
        }

        @Override
        public String toString() {
            return "post volume " + volume.priority();
        }
    }
}
