package com.crystalgraphics.vfx.sequence;

import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.CgSequenceBinding;
import com.crystalgraphics.sequence.CgSequenceChannel;
import com.crystalgraphics.sequence.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequenceSection;
import com.crystalgraphics.sequence.CgSequenceTrack;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A sequence a particle plays, baked into the {@link CgVfxNodeClip} its emitter draws: Niagara's component renderer
 * playing a sequence on each particle, compiled to GPU pieces. Each frame of the clip, 30 a second, is the sequence's
 * own evaluation of its channels at that tick, so a clip moves, fades and keys its materials as the sequence does when
 * the sequencer plays it.
 *
 * <pre>{@code
 * CgSequenceDefinition spin = (CgSequenceDefinition) deps.definition(CgSequenceKinds.SEQUENCE, "mymod:spin");
 * CgVfxEmitter blades = CgVfxEmitter.builder("blades").sequence(CgVfxClipBake.bake("blades", spin)).build();
 * }</pre>
 *
 * <pre>{@code
 * # what a baked sequence holds: one prefab spawnable, and tracks on its nodes
 * spawnables:
 *   - { id: s1, name: ring, prefab: mymod:ring }
 * tracks:
 *   - { id: t1, kind: transform, binding: { spawnable: s1, node: blade }, sections: [ { range: [0, 30], rotation: { z: { keys: [[0, 0], [30, 6.28]] } } } ] }
 *   - { id: t2, kind: property, binding: { spawnable: s1, node: blade }, property: opacity, sections: [ ... ] }
 *   - { id: t3, kind: material, binding: { spawnable: s1, material: ring }, sections: [ { range: [0, 30], colours: { _UV0Window: { r: ..., g: ..., b: ..., a: ... } } } ] }
 * }</pre>
 *
 * <ul>
 *   <li>It holds one {@code prefab} spawnable and tracks on its nodes or its materials: {@code transform},
 *       {@code property} opacity and {@code rotationEulerDeg} (degrees), and {@code material}. Anything else, a muted track aside, fails at load by name.</li>
 *   <li>A material track on a material keys every node drawing it. Its values are {@link CgVfxNodeClip#MATERIAL_SCALARS}:
 *       a vector or colour by component, {@code r g b a} as {@code x y z w}.</li>
 *   <li>A rotation is the sequence's (its units' convention); an axis no channel keys is 0, as a node track playing it
 *       sets it.</li>
 *   <li>A frame outside every section of a track keys nothing there, the clip holding the nearest key; a section's
 *       end, which it excludes, is still its last frame.</li>
 * </ul>
 */
public final class CgVfxClipBake {

    private static final String[] AXES = {"x", "y", "z"};
    private static final String COMPONENTS = "xyzw", COLOUR = "rgba";

    private CgVfxClipBake() {
    }

    /** {@code sequence} as the clip each particle of {@code who} plays. */
    public static CgVfxNodeClip bake(String who, CgSequenceDefinition sequence) {
        return builder(who, sequence).build();
    }

    /**
     * {@code sequence} baked into a clip's builder, for a caller adding what a file cannot say yet: bursts and streams
     * of children at its nodes.
     *
     * <pre>{@code
     * CgVfxNodeClip clip = CgVfxClipBake.builder("blades", spin).burst("tip", sparks, 30, 3).build();
     * }</pre>
     */
    public static CgVfxNodeClip.Builder builder(String who, CgSequenceDefinition sequence) {
        String where = who + ": sequence " + sequence.id();
        int prefabAt = -1;
        for (int i = 0; i < sequence.spawnables().size(); i++) {
            CgSequenceDefinition.Spawnable s = sequence.spawnables().get(i);
            if (!s.kind().equals("prefab")) {
                throw new CgAssetException(where + ": a " + s.kind() + " spawnable (" + s.id() + ") on a particle is not baked yet: prefabs alone");
            }
            if (prefabAt >= 0) throw new CgAssetException(where + ": a particle plays one prefab, not " + s.id() + " too");
            prefabAt = i;
        }
        if (prefabAt < 0 || !(sequence.spawnables().get(prefabAt).definition() instanceof CgVfxPrefab prefab)) {
            throw new CgAssetException(where + " spawns no prefab a particle could play");
        }
        double ticksPerFrame = sequence.tickResolution().asDecimal() / CgVfxNodeClip.FRAMES_PER_SECOND;
        int frames = Math.max(1, (int) Math.round((sequence.end() - sequence.start()) / ticksPerFrame));
        CgVfxNodeClip.Builder clip = CgVfxNodeClip.of(prefab, frames);
        for (CgSequenceTrack t : sequence.tracks()) {
            if (t.muted()) continue;
            String at = where + ": tracks." + t.id();
            CgSequenceBinding b = t.binding();
            if (b.subject() != CgSequenceBinding.Subject.SPAWNABLE || b.index() != prefabAt) {
                throw new CgAssetException(at + " binds " + b.subject() + ", which a particle's clip has none of: its prefab's nodes alone");
            }
            List<Integer> nodes = nodes(prefab, b, at);
            switch (t.kind()) {
                case "transform" -> transform(clip, t, nodes, sequence, ticksPerFrame, frames, at);
                case "property" -> {
                    if ("opacity".equals(t.property())) {
                        for (int n : nodes) key(clip, n, t, "value", CgVfxNodeClip.Channel.OPACITY, null, sequence, ticksPerFrame, frames, at);
                    } else if ("rotationEulerDeg".equals(t.property())) {
                        // A node's rotation in degrees, as the prefab spawner's node plays it.
                        for (int n : nodes) rotation(clip, n, t, "", (float) (Math.PI / 180), sequence, ticksPerFrame, frames, at);
                    } else {
                        throw new CgAssetException(at + ": a node's " + t.property() + " is not baked yet: opacity and rotationEulerDeg");
                    }
                }
                case "material" -> material(clip, t, nodes, sequence, ticksPerFrame, frames, at);
                default -> throw new CgAssetException(at + ": a " + t.kind() + " track on a particle is not baked yet: transform, property and material");
            }
        }
        return clip;
    }

    /** The nodes {@code b} names: its node, or every node drawing its material. */
    private static List<Integer> nodes(CgVfxPrefab prefab, CgSequenceBinding b, String at) {
        List<Integer> out = new ArrayList<>();
        if (b.part() == CgSequenceBinding.Part.NODE) {
            int n = prefab.node(b.partName());
            if (n < 0) throw new CgAssetException(at + ": " + prefab.id() + " has no node " + b.partName());
            out.add(n);
        } else if (b.part() == CgSequenceBinding.Part.MATERIAL) {
            for (int n : prefab.nodesDrawing(b.partName())) out.add(n);
            if (out.isEmpty()) throw new CgAssetException(at + ": no node of " + prefab.id() + " draws " + b.partName());
        } else {
            throw new CgAssetException(at + ": a binding to the prefab's " + b.part() + " is not baked yet: a node or a material");
        }
        return out;
    }

    private static void transform(CgVfxNodeClip.Builder clip, CgSequenceTrack t, List<Integer> nodes, CgSequenceDefinition seq,
                                  double ticksPerFrame, int frames, String at) {
        for (int n : nodes) {
            for (int a = 0; a < 3; a++) {
                key(clip, n, t, "translation." + AXES[a], CgVfxNodeClip.Channel.values()[CgVfxNodeClip.Channel.LOCATION_X.ordinal() + a],
                        null, seq, ticksPerFrame, frames, at);
                key(clip, n, t, "scale." + AXES[a], CgVfxNodeClip.Channel.values()[CgVfxNodeClip.Channel.SCALE_X.ordinal() + a],
                        null, seq, ticksPerFrame, frames, at);
            }
            rotation(clip, n, t, "rotation.", 1f, seq, ticksPerFrame, frames, at);
        }
        for (CgSequenceSection s : t.sections()) {
            for (CgSequenceChannel c : s.channels()) {
                String name = c.name();
                if (!name.startsWith("translation.") && !name.startsWith("rotation.") && !name.startsWith("scale.") && !name.equals("weight")) {
                    throw new CgAssetException(at + "." + name + ": a node's " + name + " is not baked yet: translation, rotation and scale");
                }
            }
        }
    }

    /**
     * A node's rotation each frame from channels {@code prefix + x, y, z} times {@code toAngle} (the sequence's angle
     * units), through its units, as the clip's Rx·Ry·Rz degrees.
     */
    private static void rotation(CgVfxNodeClip.Builder clip, int node, CgSequenceTrack t, String prefix, float toAngle,
                                 CgSequenceDefinition seq, double ticksPerFrame, int frames, String at) {
        CgUnits units = seq.units();
        float[][] keys = new float[3][];
        int[] counts = new int[3];
        boolean any = false, stepped = true;
        for (int a = 0; a < 3; a++) keys[a] = new float[2 * (frames + 1)];
        Quaternionf turn = new Quaternionf();
        Vector3f euler = new Vector3f();
        float[] angles = new float[3];
        for (int f = 0; f <= frames; f++) {
            float tick = (float) (seq.start() + f * ticksPerFrame);
            CgSequenceSection s = section(t, tick);
            if (s == null) continue;
            boolean keyed = false;
            for (int a = 0; a < 3; a++) {
                CgSequenceChannel c = s.channel(prefix + AXES[a]);
                angles[a] = c == null ? 0f : c.eval(tick) * toAngle;
                if (c != null) {
                    keyed = true;
                    stepped &= steps(c);
                }
            }
            if (!keyed) continue;
            any = true;
            try {
                units.rotationToHost(angles[0], angles[1], angles[2], turn).getEulerAnglesXYZ(euler);
            } catch (IllegalArgumentException e) {
                throw new CgAssetException(at + ".rotation: " + e.getMessage());
            }
            float[] deg = {(float) Math.toDegrees(euler.x), (float) Math.toDegrees(euler.y), (float) Math.toDegrees(euler.z)};
            for (int a = 0; a < 3; a++) {
                keys[a][2 * counts[a]] = f;
                keys[a][2 * counts[a] + 1] = deg[a];
                counts[a]++;
            }
        }
        if (!any) return;
        CgVfxNodeClip.Interp interp = stepped ? CgVfxNodeClip.Interp.CONSTANT : CgVfxNodeClip.Interp.LINEAR;
        for (int a = 0; a < 3; a++) {
            float[] k = new float[2 * counts[a]];
            System.arraycopy(keys[a], 0, k, 0, k.length);
            clip.channel(node, CgVfxNodeClip.Channel.values()[CgVfxNodeClip.Channel.ROTATION_X.ordinal() + a], interp, k);
        }
    }

    private static void material(CgVfxNodeClip.Builder clip, CgSequenceTrack t, List<Integer> nodes, CgSequenceDefinition seq,
                                 double ticksPerFrame, int frames, String at) {
        List<String> channels = new ArrayList<>();
        for (CgSequenceSection s : t.sections()) for (CgSequenceChannel c : s.channels()) if (!channels.contains(c.name())) channels.add(c.name());
        for (String name : channels) {
            if (name.equals("weight")) continue;
            String slot = slotName(name, at);
            for (int n : nodes) key(clip, n, t, name, null, slot, seq, ticksPerFrame, frames, at);
        }
    }

    /** A material channel's clip slot: {@code scalars._Glare} as {@code _Glare}, {@code colours._UV0Window.r} as {@code _UV0Window.x}. */
    private static String slotName(String channel, String at) {
        String slot;
        if (channel.startsWith("scalars.")) {
            slot = channel.substring("scalars.".length());
        } else if (channel.startsWith("vectors.") || channel.startsWith("colours.")) {
            int dot = channel.lastIndexOf('.');
            String c = channel.substring(dot + 1);
            int i = c.length() == 1 ? Math.max(COMPONENTS.indexOf(c), COLOUR.indexOf(c)) : -1;
            if (i < 0) throw new CgAssetException(at + "." + channel + ": a component x, y, z, w or r, g, b, a");
            slot = channel.substring(channel.indexOf('.') + 1, dot) + "." + COMPONENTS.charAt(i);
        } else {
            throw new CgAssetException(at + "." + channel + ": a material's " + channel + " is not baked yet: scalars, vectors and colours");
        }
        if (!CgVfxNodeClip.MATERIAL_SCALARS.contains(slot)) {
            throw new CgAssetException(at + "." + channel + ": " + slot + " has no clip slot, so no shader could read it: " + CgVfxNodeClip.MATERIAL_SCALARS);
        }
        return slot;
    }

    /** Channel {@code name} of {@code t} sampled each frame into the clip's {@code channel}, or its material slot {@code slot}. */
    private static void key(CgVfxNodeClip.Builder clip, int node, CgSequenceTrack t, String name, CgVfxNodeClip.Channel channel,
                            String slot, CgSequenceDefinition seq, double ticksPerFrame, int frames, String at) {
        float[] keys = new float[2 * (frames + 1)];
        int count = 0;
        boolean stepped = true;
        for (int f = 0; f <= frames; f++) {
            float tick = (float) (seq.start() + f * ticksPerFrame);
            CgSequenceSection s = section(t, tick);
            CgSequenceChannel c = s == null ? null : s.channel(name);
            if (c == null) continue;
            if (c.type() != CgSequenceChannel.Type.FLOAT) throw new CgAssetException(at + "." + name + ": a " + c.type() + " channel, where a clip keys numbers");
            stepped &= steps(c);
            keys[2 * count] = f;
            keys[2 * count + 1] = c.eval(tick);
            count++;
        }
        if (count == 0) return;
        float[] k = new float[2 * count];
        System.arraycopy(keys, 0, k, 0, k.length);
        CgVfxNodeClip.Interp interp = stepped ? CgVfxNodeClip.Interp.CONSTANT : CgVfxNodeClip.Interp.LINEAR;
        if (channel != null) clip.channel(node, channel, interp, k);
        else clip.material(node, slot, interp, k);
    }

    /**
     * The section of {@code t} playing at {@code tick}: the first containing it, else one ending there, so the clip's
     * last frame holds where its section ends.
     */
    private static CgSequenceSection section(CgSequenceTrack t, float tick) {
        for (CgSequenceSection s : t.sections()) if (s.contains((int) Math.floor(tick))) return s;
        for (CgSequenceSection s : t.sections()) if (s.to() == (int) Math.floor(tick)) return s;
        return null;
    }

    /** Whether every key of {@code c} holds until the next: a step the clip keeps. */
    private static boolean steps(CgSequenceChannel c) {
        CgRichCurve curve = c.curve();
        if (curve == null || curve.size() < 2) return false;
        for (int k = 0; k < curve.size() - 1; k++) if (curve.interp(k) != CgRichCurve.CONSTANT) return false;
        return true;
    }
}
