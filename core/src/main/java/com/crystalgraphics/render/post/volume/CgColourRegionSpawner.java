package com.crystalgraphics.render.post.volume;

import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawner;
import com.crystalgraphics.world.CgAttachment;

/**
 * Makes a sequence's {@code colour_region} spawnables: a {@link CgColourRegion} standing where its {@code at} or its
 * {@code transform} track puts it, sized by its scale, its fields keyed by {@code property} tracks.
 *
 * <pre>{@code
 * CgColourRegionSpawner.install(sequencer);   // CgVfxSequencer.install does it for the engine's sequencers
 * }</pre>
 *
 * <pre>{@code
 * spawnables:
 *   - { id: s2, name: flash, colour_region: { priority: 1, falloff: 0.2, colour: { gain: [2, 2, 2, 1] } } }
 * tracks:
 *   - id: t1
 *     kind: property
 *     binding: { spawnable: s2 }
 *     property: colour_region.colour.gain
 *     sections:
 *       - { id: x1, value: { a: { keys: [[0, 2.0], [18000, 1.0]] } } }   # its gain's scalar, 2 down to 1
 * }</pre>
 *
 * <ul>
 *   <li>A property track names {@code colour_region.} then a scalar ({@code intensity}, {@code inner}, {@code outer},
 *       {@code falloff}, {@code temperature}, {@code tint}) or a range's control ({@code colour.gain},
 *       {@code shadows.saturation}), its components {@code r g b a} or {@code x y z w}.</li>
 *   <li>A block that makes no region (a key it lacks, {@code affected}) fails as its play starts, naming it.</li>
 * </ul>
 */
public final class CgColourRegionSpawner implements CgSequenceSpawner {

    private static final String[] SCALARS = {"intensity", "inner", "outer", "falloff", "temperature", "tint"};
    /** A range's control's slot: this plus range * 5 plus control. */
    private static final int GRADES = 16;
    private static final String PREFIX = "colour_region.";

    /** Registers one for the {@code colour_region} kind on {@code sequencer}. */
    public static void install(CgSequencer sequencer) {
        sequencer.spawner("colour_region", new CgColourRegionSpawner());
    }

    @Override
    public void check(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable spawnable) {
        CgColourRegion.of(block(definition, spawnable), path(definition, spawnable));
    }

    @Override
    public CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        CgColourRegion region = CgColourRegion.of(block(player.definition(), spawnable), path(player.definition(), spawnable));
        Spawned s = new Spawned(region, at);
        s.update();
        return s;
    }

    private static Map<?, ?> block(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable spawnable) {
        if (!(spawnable.tree().get("colour_region") instanceof Map<?, ?> block)) {
            throw new CgAssetException(path(definition, spawnable) + ": names no colour_region block");
        }
        return block;
    }

    private static String path(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable spawnable) {
        return definition.id() + ": spawnables." + spawnable.id() + ".colour_region";
    }

    private static final class Spawned implements CgSequenceSpawned {
        private final CgColourRegion region;
        private final CgAttachment at;
        private final CgRenderStage.Registration shown;
        private final double[] point = new double[3];
        private final Quaternionf turn = new Quaternionf();
        private final Vector3f scale = new Vector3f();

        Spawned(CgColourRegion region, CgAttachment at) {
            this.region = region;
            this.at = at;
            this.shown = region.show();
        }

        @Override
        public int slot(String name) {
            // A property track names its field under the spawnable's block: colour_region.colour.gain
            if (name.startsWith(PREFIX)) name = name.substring(PREFIX.length());
            for (int i = 0; i < SCALARS.length; i++) if (SCALARS[i].equals(name)) return i;
            int dot = name.indexOf('.');
            if (dot < 0) return -1;
            CgColourRegion.Range range = CgColourRegion.range(name.substring(0, dot));
            CgColourRegion.Control control = CgColourRegion.control(name.substring(dot + 1));
            return range == null || control == null ? -1 : GRADES + range.ordinal() * 5 + control.ordinal();
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            if (slot >= GRADES) {
                CgColourRegion.Range range = CgColourRegion.Range.values()[(slot - GRADES) / 5];
                CgColourRegion.Control control = CgColourRegion.Control.values()[(slot - GRADES) % 5];
                for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) region.grade(range, control, i, values[i]);
                return;
            }
            if ((mask & 1) == 0) return;
            float v = values[0];
            switch (slot) {
                case 0 -> region.intensity(v);
                case 1 -> region.fade(v, region.outer());
                case 2 -> region.fade(region.inner(), v);
                case 3 -> region.falloff(v);
                case 4 -> region.temperature(v, region.tint());
                case 5 -> region.temperature(region.temperature(), v);
                default -> {
                }
            }
        }

        @Override
        public void get(int slot, float[] out) {
            if (slot >= GRADES) {
                CgColourRegion.Range range = CgColourRegion.Range.values()[(slot - GRADES) / 5];
                CgColourRegion.Control control = CgColourRegion.Control.values()[(slot - GRADES) % 5];
                for (int i = 0; i < 4; i++) out[i] = region.grade(range, control, i);
                return;
            }
            out[0] = switch (slot) {
                case 0 -> region.intensity();
                case 1 -> region.inner();
                case 2 -> region.outer();
                case 3 -> region.falloff();
                case 4 -> region.temperature();
                case 5 -> region.tint();
                default -> 0f;
            };
        }

        /** Where its place stands this frame, turned and scaled as its transform keys it. */
        @Override
        public void update() {
            if (!at.place(point, turn)) return;
            at.scale(null, scale);
            region.place(point[0], point[1], point[2], turn, scale.x, scale.y, scale.z);
        }

        @Override
        public void destroy(boolean graceful) {
            shown.close();
        }

        @Override
        public String toString() {
            return "colour region";
        }
    }
}
