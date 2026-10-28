package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.CgSequenceBinding;
import com.crystalgraphics.sequence.CgSequenceChannel;
import com.crystalgraphics.sequence.CgSequenceDefinition;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceSection;
import com.crystalgraphics.sequence.CgSequenceSpawned;
import com.crystalgraphics.sequence.CgSequenceSpawner;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequenceTrack;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.system.CgVfxSystemSpawner;
import com.crystalgraphics.world.CgAttachment;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes a sequence's {@code prefab} spawnables: a {@link CgVfxPrefabEffect} the sequence owns, Unreal's spawned actor,
 * whose nodes its tracks key ({@code binding: { spawnable, node }}) and on whose nodes other spawnables sit
 * ({@code at: { spawnable, node }}).
 *
 * <pre>{@code
 * CgVfxSystemSpawner systems = new CgVfxSystemSpawner(CgVfx.world());
 * sequencer.spawner("system", systems);
 * CgVfxPrefabSpawner.install(sequencer, systems);    // its prefabs sort with its systems, one group a play
 * }</pre>
 *
 * <pre>{@code
 * spawnables:
 *   - { id: s1, name: shock, at: { role: caster, socket: root }, prefab: nsuns4_kit:1efcmn/1efc_shock08 }
 * tracks:
 *   - { id: t1, kind: property, binding: { spawnable: s1, node: "1efc_shock08" }, property: opacity, sections: [ ... ] }
 * }</pre>
 *
 * <p>A material binding ({@code binding: { spawnable, material: blade }}) keys every node drawing it, named by its id
 * or its id's last segment. What a node answers: {@code translation}, {@code rotation} and {@code scale} (a transform track),
 * {@code rotationEulerDeg} (CC2's node rotation, degrees), {@code opacity}, and each material value a track keys on
 * it.</p>
 *
 * <ul>
 *   <li>Each spawnable's prefab is made once, its keyed material values its parameters ({@link CgVfxPrefab#keyed}), and
 *       checked drawable then: a node drawing what is not built yet fails at play, naming it.</li>
 *   <li>A spawnable's {@code nodes} rest is set on its nodes as it spawns.</li>
 *   <li>Ending it removes it at once, gracefully or not: a prefab has nothing in flight.</li>
 *   <li>An inline {@code prefab} block is not built yet: it fails at play, naming it.</li>
 * </ul>
 */
public final class CgVfxPrefabSpawner implements CgSequenceSpawner {

    private static final int TRANSLATION = 0, ROTATION = 1, SCALE = 2, EULER_DEGREES = 3, OPACITY = 4, PARAMS = 8;

    private final CgVfxSystem vfx;
    private final CgVfxSystemSpawner systems;
    /** Each spawnable's prefab, keyed for its tracks: made at its first spawn. */
    private final Map<CgSequenceDefinition.Spawnable, CgVfxPrefab> prefabs = new IdentityHashMap<>();

    public CgVfxPrefabSpawner(CgVfxSystem vfx, CgVfxSystemSpawner systems) {
        this.vfx = vfx;
        this.systems = systems;
    }

    /** Registers one for the {@code prefab} kind on {@code sequencer}, playing into the client world's system. */
    public static void install(CgSequencer sequencer, CgVfxSystemSpawner systems) {
        sequencer.spawner("prefab", new CgVfxPrefabSpawner(CgVfx.world(), systems));
    }

    @Override
    public CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        CgVfxPrefab prefab = prefabs.computeIfAbsent(spawnable, s -> prefab(player.definition(), s));
        double[] point = new double[3];
        Quaternionf turn = new Quaternionf();
        if (!at.place(point, turn)) {
            point[0] = point[1] = point[2] = 0;
            turn.identity();
        }
        CgVfxPrefabEffect effect = new CgVfxPrefabEffect(prefab, point[0], point[1], point[2]).sortGroup(systems.group(player));
        if (spawnable.follow()) effect.follow(at);
        else effect.orient(turn);
        Spawned spawned = new Spawned(effect, player.definition().units());
        spawned.rest(spawnable.tree().get("nodes"), player.definition().id() + ": spawnables." + spawnable.id() + ".nodes");
        vfx.play(effect);
        return spawned;
    }

    /** {@code s}'s prefab, its material values keyed by {@code definition}'s tracks made parameters. */
    private static CgVfxPrefab prefab(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable s) {
        String who = definition.id() + ": spawnables." + s.id();
        if (!(s.tree().get("prefab") instanceof String id)) throw new CgAssetException(who + ".prefab: an inline prefab is not built yet");
        CgVfxPrefab prefab = CgVfxAssets.<CgVfxPrefab>ref(CgBodyKinds.PREFAB, id).get();
        int index = definition.spawnables().indexOf(s);
        Map<String, Set<String>> keyed = new LinkedHashMap<>();
        for (CgSequenceTrack t : definition.tracks()) {
            CgSequenceBinding b = t.binding();
            if (!t.kind().equals("material") || b.subject() != CgSequenceBinding.Subject.SPAWNABLE || b.index() != index
                    || b.part() != CgSequenceBinding.Part.NODE && b.part() != CgSequenceBinding.Part.MATERIAL) {
                continue;
            }
            // A material binding keys every node drawing it.
            List<String> nodes = new ArrayList<>();
            if (b.part() == CgSequenceBinding.Part.NODE) nodes.add(b.partName());
            else for (int n : prefab.nodesDrawing(b.partName())) nodes.add(prefab.name(n));
            for (CgSequenceSection section : t.sections()) {
                for (CgSequenceChannel c : section.channels()) {
                    String name = c.name();
                    // A vector or a colour keys a component a channel: its property is the name between.
                    String property = name.startsWith("scalars.") ? name.substring("scalars.".length())
                            : name.startsWith("vectors.") || name.startsWith("colours.")
                            ? name.substring(name.indexOf('.') + 1, name.lastIndexOf('.')) : null;
                    if (property != null) for (String n : nodes) keyed.computeIfAbsent(n, k -> new LinkedHashSet<>()).add(property);
                }
            }
        }
        if (s.tree().get("nodes") instanceof Map<?, ?> nodes) {
            for (Map.Entry<?, ?> n : nodes.entrySet()) {
                if (n.getValue() instanceof Map<?, ?> rest && rest.get("values") instanceof Map<?, ?> values) {
                    for (Object k : values.keySet()) keyed.computeIfAbsent((String) n.getKey(), x -> new LinkedHashSet<>()).add((String) k);
                }
            }
        }
        CgVfxPrefab made = prefab.keyed(keyed);
        made.requireDrawable(who);
        return made;
    }

    /** A spawned prefab: its effect, and a target a node. */
    private static final class Spawned implements CgSequenceSpawned {
        private final CgVfxPrefabEffect effect;
        private final CgVfxPrefab prefab;
        private final CgUnits units;
        private final Node[] nodes;
        private final Map<String, Material> materials = new LinkedHashMap<>();
        @Nullable
        private CgAttachment[] attachments;

        Spawned(CgVfxPrefabEffect effect, CgUnits units) {
            this.effect = effect;
            this.prefab = effect.instance().prefab();
            this.units = units;
            this.nodes = new Node[prefab.nodes()];
        }

        /** The sequence's rest for its nodes, from its spawnable's {@code nodes}. */
        void rest(@Nullable Object block, String where) {
            if (!(block instanceof Map<?, ?> rests)) return;
            float[] v = new float[4];
            for (Map.Entry<?, ?> e : rests.entrySet()) {
                Node node = (Node) part(CgSequenceBinding.Part.NODE, (String) e.getKey());
                if (node == null) throw new CgAssetException(where + "." + e.getKey() + ": " + prefab.id() + " has no such node");
                Map<?, ?> rest = (Map<?, ?>) e.getValue();
                if (rest.get("location") instanceof List<?> l) node.set(TRANSLATION, three(units.toHost(l, CgQuantity.POSITION), v), 7);
                if (rest.get("rotation") != null) {
                    Quaternionf q = units.rotationToHost(rest.get("rotation"), new Quaternionf());
                    effect.instance().rotation(node.index, q.x, q.y, q.z, q.w);
                }
                if (rest.get("scale") instanceof List<?> s) node.set(SCALE, three(units.toHost(s, CgQuantity.SCALE), v), 7);
                if (rest.get("opacity") instanceof Number a) effect.instance().opacity(node.index, a.floatValue());
                if (rest.get("values") instanceof Map<?, ?> values) {
                    for (Map.Entry<?, ?> p : values.entrySet()) {
                        float[] value = p.getValue() instanceof List<?> l ? floats(l) : new float[] {((Number) p.getValue()).floatValue()};
                        node.set(PARAMS + prefab.keyedNames(node.index).indexOf((String) p.getKey()), value, 15);
                    }
                }
            }
        }

        private static float[] floats(List<?> l) {
            float[] out = new float[4];
            for (int i = 0; i < Math.min(4, l.size()); i++) out[i] = ((Number) l.get(i)).floatValue();
            return out;
        }

        private static float[] three(Object list, float[] out) {
            List<?> l = (List<?>) list;
            for (int i = 0; i < 3; i++) out[i] = ((Number) l.get(i)).floatValue();
            return out;
        }

        @Override
        @Nullable
        public CgSequenceTarget part(CgSequenceBinding.Part part, String name) {
            if (part == CgSequenceBinding.Part.MATERIAL) {
                int[] drawing = prefab.nodesDrawing(name);
                if (drawing.length == 0) return null;
                return materials.computeIfAbsent(name, m -> {
                    Node[] of = new Node[drawing.length];
                    for (int k = 0; k < of.length; k++) of[k] = (Node) part(CgSequenceBinding.Part.NODE, prefab.name(drawing[k]));
                    return new Material(m, of);
                });
            }
            if (part != CgSequenceBinding.Part.NODE) return null;
            int i = prefab.node(name);
            if (i < 0) return null;
            if (nodes[i] == null) nodes[i] = new Node(i);
            return nodes[i];
        }

        @Override
        @Nullable
        public CgAttachment attachment(String name) {
            int i = prefab.node(name);
            if (i < 0) return null;
            if (attachments == null) attachments = new CgAttachment[prefab.nodes()];
            if (attachments[i] == null) attachments[i] = effect.attachment(i);
            return attachments[i];
        }

        @Override
        public void update() {
            effect.evaluate();
        }

        @Override
        public void destroy(boolean graceful) {
            effect.kill();
        }

        @Override
        public String toString() {
            return "prefab " + prefab.id();
        }

        /** A material as a target: each value it keys written to every node drawing it. */
        private final class Material implements CgSequenceTarget {
            final String name;
            final Node[] nodes;
            /** Each slot a section asked for, its slot on each node. */
            private final List<int[]> slots = new ArrayList<>();

            Material(String name, Node[] nodes) {
                this.name = name;
                this.nodes = nodes;
            }

            @Override
            public int slot(String property) {
                int[] on = new int[nodes.length];
                for (int k = 0; k < nodes.length; k++) {
                    on[k] = prefab.keyedNames(nodes[k].index).indexOf(property);
                    if (on[k] < 0) return -1;
                    on[k] += PARAMS;
                }
                slots.add(on);
                return slots.size() - 1;
            }

            @Override
            public void set(int slot, float[] values, int mask) {
                int[] on = slots.get(slot);
                for (int k = 0; k < nodes.length; k++) nodes[k].set(on[k], values, mask);
            }

            @Override
            public void get(int slot, float[] out) {
                nodes[0].get(slots.get(slot)[0], out);
            }

            @Override
            public String toString() {
                return "material " + name + " of prefab " + prefab.id();
            }
        }

        /** One node as a target. */
        private final class Node implements CgSequenceTarget {
            final int index;
            private final float[] local = new float[CgVfxPrefab.REST];
            /** The angles last keyed, by a transform track and by {@code rotationEulerDeg}. */
            private final float[] angles = new float[3], degrees = new float[3], vector = new float[4];
            private final Quaternionf turn = new Quaternionf();

            Node(int index) {
                this.index = index;
            }

            @Override
            public int slot(String name) {
                switch (name) {
                    case "translation":
                        return TRANSLATION;
                    case "rotation":
                        return ROTATION;
                    case "scale":
                        return SCALE;
                    case "rotationEulerDeg":
                        return EULER_DEGREES;
                    case "opacity":
                        return OPACITY;
                    default:
                        int k = prefab.keyedNames(index).indexOf(name);
                        return k < 0 ? -1 : PARAMS + k;
                }
            }

            @Override
            public void set(int slot, float[] values, int mask) {
                CgVfxPrefabInstance instance = effect.instance();
                switch (slot) {
                    case TRANSLATION, SCALE -> {
                        instance.local(index, local);
                        int o = slot == TRANSLATION ? 0 : 7;
                        for (int i = 0; i < 3; i++) if ((mask & 1 << i) != 0) local[o + i] = values[i];
                        if (slot == TRANSLATION) instance.location(index, local[0], local[1], local[2]);
                        else instance.scale(index, local[7], local[8], local[9]);
                    }
                    case ROTATION, EULER_DEGREES -> {
                        float[] to = slot == ROTATION ? angles : degrees;
                        for (int i = 0; i < 3; i++) if ((mask & 1 << i) != 0) to[i] = values[i];
                        float k = slot == ROTATION ? 1f : (float) (Math.PI / 180);
                        units.rotationToHost(to[0] * k, to[1] * k, to[2] * k, turn);
                        instance.rotation(index, turn.x, turn.y, turn.z, turn.w);
                    }
                    case OPACITY -> {
                        if ((mask & 1) != 0) instance.opacity(index, values[0]);
                    }
                    default -> {
                        CgVfxParam p = prefab.param(index, prefab.keyedNames(index).get(slot - PARAMS));
                        if (p instanceof CgVfxParam.Scalar s) {
                            if ((mask & 1) != 0) effect.set(s, values[0]);
                        } else {
                            CgVfxParam.Color c = (CgVfxParam.Color) p;
                            for (int i = 0; i < 4; i++) vector[i] = (mask & 1 << i) != 0 ? values[i] : effect.get(c, i);
                            effect.set(c, vector[0], vector[1], vector[2], vector[3]);
                        }
                    }
                }
            }

            @Override
            public void get(int slot, float[] out) {
                CgVfxPrefabInstance instance = effect.instance();
                switch (slot) {
                    case TRANSLATION, SCALE -> {
                        instance.local(index, local);
                        System.arraycopy(local, slot == TRANSLATION ? 0 : 7, out, 0, 3);
                    }
                    case ROTATION -> System.arraycopy(angles, 0, out, 0, 3);
                    case EULER_DEGREES -> System.arraycopy(degrees, 0, out, 0, 3);
                    case OPACITY -> out[0] = instance.opacity(index);
                    default -> {
                        CgVfxParam p = prefab.param(index, prefab.keyedNames(index).get(slot - PARAMS));
                        if (p instanceof CgVfxParam.Scalar s) {
                            out[0] = effect.get(s);
                        } else {
                            for (int i = 0; i < Math.min(4, out.length); i++) out[i] = effect.get((CgVfxParam.Color) p, i);
                        }
                    }
                }
            }

            @Override
            public String toString() {
                return "node " + prefab.name(index) + " of prefab " + prefab.id();
            }
        }
    }
}
