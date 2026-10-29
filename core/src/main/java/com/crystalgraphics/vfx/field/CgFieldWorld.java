package com.crystalgraphics.vfx.field;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.vfx.CgVfxEffect;

/**
 * The client world's field instances, flattened once a frame for the particle steps: Chaos's physics field manager
 * ({@code UPhysicsFieldComponent}). Each group keeps the {@link #PER_GROUP} instances nearest the camera that reach
 * it; their graphs go into one buffer the Step's {@code physics_field} modules read through {@code fx_field.glsl}.
 * Render thread.
 *
 * <pre>{@code
 * CgFieldWorld.Instance pull = CgFieldWorld.add(placement, effect);   // as its system starts
 * pull.place(x, y, z, effect.orientation(), age);                   // each tick
 * pull.remove();                                                    // as it ends; a dead effect's go by themselves
 * }</pre>
 *
 * <ul>
 *   <li>A group is a name its modules and placements share, numbered once: {@link #group}.</li>
 *   <li>Positions are relative to the block {@link #around} last put the camera in, as the entity boxes are.</li>
 *   <li>A graph holding a node with no GPU evaluation yet ({@code noise}) fails as it is added, naming it.</li>
 * </ul>
 */
public final class CgFieldWorld {

    /** The most instances a group evaluates, nearest the camera first. */
    public static final int PER_GROUP = 16;
    /** The most groups. */
    public static final int GROUPS = 32;
    /** The most instances flattened a frame, every group's together. */
    public static final int MAX_INSTANCES = 128;
    /** How far from the camera a bounded instance still evaluates, past its own reach, in blocks. */
    public static final double REACH = 128.0;
    /** Words of an instance's header, and of a node: its opcode and inputs, then 16 numbers. */
    static final int INSTANCE_WORDS = 20, NODE_WORDS = 20;

    public static final String PROPERTIES = """
                _FieldAnchorX ("The block the fields are relative to: x", int) = 0
                _FieldAnchorY ("y",                                     int) = 0
                _FieldAnchorZ ("z",                                     int) = 0
            """;

    /** The kinds the GPU evaluates, each under its place plus one; {@code fx_field.glsl}'s {@code FX_FIELD_*}. */
    static final List<CgFieldNode.Kind> OPCODES = List.of(CgFieldNodes.RADIAL_FALLOFF, CgFieldNodes.PLANE_FALLOFF,
            CgFieldNodes.BOX_FALLOFF, CgFieldNodes.WAVE_SCALAR, CgFieldNodes.UNIFORM_SCALAR, CgFieldNodes.UNIFORM_VECTOR,
            CgFieldNodes.RADIAL_VECTOR, CgFieldNodes.ROTATED_RADIAL_VECTOR, CgFieldNodes.RANDOM_VECTOR, CgFieldNodes.OPERATOR,
            CgFieldNodes.CULLING);

    private static final Map<String, Integer> GROUP_IDS = new ConcurrentHashMap<>();
    private static final List<Instance> INSTANCES = new ArrayList<>();
    private static final List<Instance> FREE = new ArrayList<>();

    private static double cameraX, cameraY, cameraZ;
    private static int anchorX, anchorY, anchorZ;
    private static long flattenedAt = -1;
    private static CgGraphBuffer buffer;
    private static final int[] WORDS = new int[GROUPS * 4 + MAX_INSTANCES * (INSTANCE_WORDS + CgFieldDefinition.MAX_NODES * NODE_WORDS)];
    private static final ByteBuffer STAGING = ByteBuffer.allocateDirect(WORDS.length * 4).order(ByteOrder.nativeOrder());
    private static int used = GROUPS * 4;
    private static final Instance[] NEAREST = new Instance[PER_GROUP];
    private static final double[] DISTANCE = new double[PER_GROUP];
    private static final Quaternionf INVERSE = new Quaternionf();
    private static final Vector3f CENTRE = new Vector3f();

    static {
        // fx_field.glsl reads each kind's numbers at fixed places: its declaration's order, which these hold.
        check(CgFieldNodes.RADIAL_FALLOFF, "magnitude", 0, "min_range", 1, "max_range", 2, "default", 3, "radius", 4, "position", 5, "falloff", 8);
        check(CgFieldNodes.PLANE_FALLOFF, "distance", 4, "position", 5, "normal", 8, "falloff", 11);
        check(CgFieldNodes.BOX_FALLOFF, "transform", 4, "falloff", 14);
        check(CgFieldNodes.WAVE_SCALAR, "magnitude", 0, "position", 1, "wavelength", 4, "period", 5, "function", 6, "falloff", 7);
        check(CgFieldNodes.UNIFORM_VECTOR, "magnitude", 0, "direction", 1);
        check(CgFieldNodes.RADIAL_VECTOR, "magnitude", 0, "position", 1);
        check(CgFieldNodes.ROTATED_RADIAL_VECTOR, "magnitude", 0, "position", 1, "angle", 4, "axis", 5);
        check(CgFieldNodes.OPERATOR, "magnitude", 0, "op", 1);
        check(CgFieldNodes.CULLING, "op", 0);
    }

    private CgFieldWorld() {
    }

    private static void check(CgFieldNode.Kind kind, Object... at) {
        for (int i = 0; i < at.length; i += 2) {
            if (kind.offset((String) at[i]) != (Integer) at[i + 1]) {
                throw new IllegalStateException(kind.name() + "." + at[i] + " moved in its declaration: fx_field.glsl reads it at " + at[i + 1]);
            }
        }
    }

    /** {@code name}'s group number, the same for every caller. Any thread. */
    public static int group(String name) {
        Integer known = GROUP_IDS.get(name);
        if (known != null) return known;
        synchronized (GROUP_IDS) {
            known = GROUP_IDS.get(name);
            if (known != null) return known;
            if (GROUP_IDS.size() == GROUPS) throw new IllegalArgumentException("field group " + name + " is past the " + GROUPS + " groups");
            GROUP_IDS.put(name, GROUP_IDS.size());
            return GROUP_IDS.size() - 1;
        }
    }

    /** The name group {@code id} was given. */
    public static String groupName(int id) {
        for (Map.Entry<String, Integer> e : GROUP_IDS.entrySet()) if (e.getValue() == id) return e.getKey();
        throw new IllegalArgumentException("no field group " + id);
    }

    /** One placed field: where it is, how it turns, how old it is. */
    public static final class Instance {
        CgFieldPlacement placement;
        CgFieldDefinition.Graph graph;
        CgVfxEffect<?> owner;
        int group;
        double x, y, z;
        float age;
        final Quaternionf rotation = new Quaternionf();
        boolean live;

        /** Puts it at {@code (x, y, z)}, turned by {@code rotation}, {@code age} seconds into its life. */
        public Instance place(double x, double y, double z, Quaternionfc rotation, float age) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.rotation.set(rotation);
            this.age = age;
            return this;
        }

        /** Ends it; nothing reads it after. */
        public void remove() {
            if (!live) return;
            live = false;
            owner = null;
            INSTANCES.remove(this);
            FREE.add(this);
        }

        public boolean live() {
            return live;
        }
    }

    /** A live instance of {@code placement}, played by {@code owner}, until {@link Instance#remove} or its owner dies. */
    public static Instance add(CgFieldPlacement placement, CgVfxEffect<?> owner) {
        if (!(placement.field() instanceof CgFieldDefinition.Graph graph)) {
            throw new IllegalArgumentException(placement.id() + ": a grid placed as a field is read by vector_field, not physics_field");
        }
        for (CgFieldNode n : graph.nodes()) {
            if (!OPCODES.contains(n.kind())) {
                throw new IllegalArgumentException(placement.id() + ": its node " + n.id() + ", a " + n.kind().name() + ", has no GPU evaluation yet");
            }
        }
        prune();
        Instance i = FREE.isEmpty() ? new Instance() : FREE.remove(FREE.size() - 1);
        i.placement = placement;
        i.graph = graph;
        i.owner = owner;
        i.group = group(placement.group());
        i.live = true;
        i.age = 0f;
        i.rotation.identity();
        INSTANCES.add(i);
        return i;
    }

    private static void prune() {
        for (int k = INSTANCES.size() - 1; k >= 0; k--) {
            Instance i = INSTANCES.get(k);
            if (i.owner != null && i.owner.state() == CgVfxEffect.State.DEAD) i.remove();
        }
    }

    /**
     * Centres this frame on {@code (x, y, z)}, the stage's camera, before any instance row is written: the anchor
     * every position is relative to.
     */
    public static void around(double x, double y, double z) {
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        anchorX = (int) Math.floor(x);
        anchorY = (int) Math.floor(y);
        anchorZ = (int) Math.floor(z);
    }

    /** Flattens this frame's instances and uploads them, once a frame. */
    public static void upload(CgRecording recording) {
        long frame = CgFrameRing.frame();
        if (frame == flattenedAt) return;
        flattenedAt = frame;
        flatten();
        if (buffer == null) {
            buffer = CgGraphBuffer.persistent("vfx.fields", CgBufferDesc.of(WORDS.length * 4L, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        STAGING.clear();
        for (int w = 0; w < used; w++) STAGING.putInt(w * 4, WORDS[w]);
        STAGING.limit(used * 4);
        recording.update(buffer, 0, STAGING);
    }

    /**
     * Writes the words the GPU reads: a group table of {@link #GROUPS} uvec4s (its first instance's uvec4 and its
     * count), then each instance (target, node count, world-oriented; its place and weight; its inverse turn; its bounds
     * with its variance and its age), each followed by its nodes (opcode and inputs plus one, then 16 numbers).
     */
    static void flatten() {
        prune();
        Arrays.fill(WORDS, 0, GROUPS * 4, 0);
        int at = GROUPS * 4, total = 0;
        for (int g = 0; g < GROUPS && g < GROUP_IDS.size(); g++) {
            int n = Math.min(nearest(g), MAX_INSTANCES - total);
            if (n == 0) continue;
            WORDS[g * 4] = at / 4;
            WORDS[g * 4 + 1] = n;
            for (int k = 0; k < n; k++, total++) at = write(NEAREST[k], at);
        }
        used = at;
    }

    /** {@code group}'s instances that reach the camera, nearest first, into {@link #NEAREST}. */
    private static int nearest(int group) {
        int n = 0;
        for (Instance i : INSTANCES) {
            if (i.group != group || !i.placement.enabled()) continue;
            double dx = i.x - cameraX, dy = i.y - cameraY, dz = i.z - cameraZ;
            double d = dx * dx + dy * dy + dz * dz;
            CgFieldBounds b = i.placement.bounds();
            if (b.bounded()) {
                double r = 0.5 * Math.sqrt(sq(b.maxX() - b.minX()) + sq(b.maxY() - b.minY()) + sq(b.maxZ() - b.minZ()));
                i.rotation.transform(CENTRE.set((b.minX() + b.maxX()) * 0.5f, (b.minY() + b.maxY()) * 0.5f, (b.minZ() + b.maxZ()) * 0.5f));
                double cx = i.x + CENTRE.x - cameraX, cy = i.y + CENTRE.y - cameraY, cz = i.z + CENTRE.z - cameraZ;
                if (Math.sqrt(cx * cx + cy * cy + cz * cz) > REACH + r) continue;
            }
            int k = n;
            if (n == PER_GROUP) {
                if (d >= DISTANCE[PER_GROUP - 1]) continue;
                k = PER_GROUP - 1;
            } else {
                n++;
            }
            while (k > 0 && DISTANCE[k - 1] > d) {
                DISTANCE[k] = DISTANCE[k - 1];
                NEAREST[k] = NEAREST[k - 1];
                k--;
            }
            DISTANCE[k] = d;
            NEAREST[k] = i;
        }
        return n;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int write(Instance i, int at) {
        CgFieldDefinition.Graph g = i.graph;
        CgFieldPlacement p = i.placement;
        CgFieldBounds b = p.bounds();
        WORDS[at] = g.target().index();
        WORDS[at + 1] = g.nodes().size();
        WORDS[at + 2] = p.world() ? 1 : 0;
        WORDS[at + 3] = 0;
        f(at + 4, (float) (i.x - anchorX));
        f(at + 5, (float) (i.y - anchorY));
        f(at + 6, (float) (i.z - anchorZ));
        f(at + 7, p.weight());
        // The inverse turn takes a point into the field's own space; a world-oriented field keeps the world's axes.
        if (p.world()) INVERSE.identity();
        else i.rotation.invert(INVERSE);
        f(at + 8, INVERSE.x);
        f(at + 9, INVERSE.y);
        f(at + 10, INVERSE.z);
        f(at + 11, INVERSE.w);
        f(at + 12, finite(b.minX()));
        f(at + 13, finite(b.minY()));
        f(at + 14, finite(b.minZ()));
        f(at + 15, g.magnitudeVar());
        f(at + 16, finite(b.maxX()));
        f(at + 17, finite(b.maxY()));
        f(at + 18, finite(b.maxZ()));
        f(at + 19, i.age);
        at += INSTANCE_WORDS;
        for (CgFieldNode n : g.nodes()) {
            WORDS[at] = OPCODES.indexOf(n.kind()) + 1;
            for (int k = 0; k < 3; k++) WORDS[at + 1 + k] = k < n.inputs().length ? n.inputs()[k] + 1 : 0;
            float[] params = n.params();
            for (int k = 0; k < 16; k++) f(at + 4 + k, k < params.length ? params[k] : 0f);
            at += NODE_WORDS;
        }
        return at;
    }

    /** An infinite side as the largest float: a shader compares against it without an infinity. */
    private static float finite(float v) {
        return Float.isFinite(v) ? v : v > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
    }

    private static void f(int at, float v) {
        WORDS[at] = Float.floatToRawIntBits(v);
    }

    public static void bind(CgDispatch d) {
        d.bind("FIELDS", buffer).set("_FieldAnchorX", anchorX).set("_FieldAnchorY", anchorY).set("_FieldAnchorZ", anchorZ);
    }

    /** The block the fields are relative to on an axis (0 x, 1 y, 2 z). */
    public static int anchor(int axis) {
        return axis == 0 ? anchorX : axis == 1 ? anchorY : anchorZ;
    }

    /** The words of the last flatten, the first {@link #used()} of them. Tests. */
    static int[] words() {
        return WORDS;
    }

    static int used() {
        return used;
    }

    /** Every instance live now. Tests. */
    static int live() {
        prune();
        return INSTANCES.size();
    }

    /** A dying context took the buffer. */
    public static void forget() {
        buffer = null;
        flattenedAt = -1;
    }
}
