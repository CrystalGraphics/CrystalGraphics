package com.crystalgraphics.animation.rig;

import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.api.animation.CgVirtualBone;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a skeleton is read by everything that works on a body rather than on bones: its retarget root, its pelvis and its
 * named chains (a spine, each arm, each leg), each chain optionally ending in an IK goal. Retargeting maps one rig's
 * chains onto another's; foot placement and limb IK take their bones from it. One rig serves every mesh on a skeleton.
 *
 * <pre>{@code
 * // the default for a skeleton of the standard humanoid names, or of GeckoLib's humanoid: no file, no code
 * CgRig rig = CgRigs.detect(mesh.skeleton(), CgRigAdapters.CRYSTALGRAPHICS);
 *
 * // a rig of your own, for a model with other names
 * CgRig golem = CgRig.builder("mymod:golem", CgRigAdapters.GECKOLIB).root("root").pelvis("torso")
 *         .chain("spine", "torso", "chest")
 *         .chain("arm_l", "arm_l_upper", "arm_l_hand", "hand_l")
 *         .chain("leg_l", "leg_l_upper", "leg_l_foot", "foot_l").bend("leg_l", 0f, 0f, -1f)   // its knee bends to -z
 *         .build();
 * }</pre>
 *
 * <pre>{@code
 * # rigs/mymod/golem.yaml: the same as data
 * model: mymod:geo/golem.geo.json
 * adapter: geckolib
 * root: root
 * pelvis: torso
 * chains:
 *   - { id: c1, name: spine, start: torso, end: chest }
 *   - { id: c2, name: leg_l, start: leg_l_upper, end: leg_l_foot, goal: foot_l, bend: [0, 0, -1] }
 *   - { id: c3, name: arm_l, start: arm_l_upper, end: arm_l_hand, goal: hand_l, rides: chest }
 * }</pre>
 *
 * <ul>
 *   <li>A chain runs from {@code start} down to {@code end}, which must be {@code start} or below it.</li>
 *   <li>A goal names the IK bone placed for the chain's end ({@code foot_l}): {@link #ikBones} makes it a virtual
 *       bone, so a skeleton exported without IK bones still has one.</li>
 *   <li>{@code rides} names the bone a chain's start moves with when the skeleton hangs it from elsewhere: a model
 *       whose arms hang from its root needs its arms to ride its chest, or they stay put as the chest leans.</li>
 *   <li>{@code pivot} is where a chain turns, for a model whose bone pivots elsewhere: GeckoLib's humanoid pivots its
 *       torso at its neck, so its torso chain turns about its hips.</li>
 *   <li>{@code bend} is the way a chain's middle joint bends, in the model's space, for a chain its clips hold
 *       straight; a chain bent in its clips keeps the clip's bend.</li>
 *   <li>Which way the model faces and which bones only rotate are its adapter's ({@link CgRigAdapter}), never the
 *       rig's.</li>
 * </ul>
 */
public final class CgRig {

    /**
     * A named run of bones, {@code start} down to {@code end}, optionally ending in an IK goal.
     *
     * @param name  what it is called; retargeting maps chains by it ({@code arm_l})
     * @param start its first bone, nearest the root
     * @param end   its last bone
     * @param goal  the IK bone for its end, or null for a chain no IK reaches
     * @param bend  the way its middle joint bends in the model's space, or null to keep the clips' bend
     * @param rides a bone its start moves with as if hung from it, or null: for a model whose limbs hang from its root
     *              rather than its torso (GeckoLib's humanoid's arms and head), so they follow the torso's lean
     * @param pivot the point it turns about, in the model's space at rest, or null for its start's own pivot: for a
     *              model pivoted elsewhere than the joint (GeckoLib's humanoid's torso, pivoted at its neck)
     */
    public record Chain(String name, String start, String end, @Nullable String goal, @Nullable Vector3fc bend,
                        @Nullable String rides, @Nullable Vector3fc pivot) {
        public Chain {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            bend = bend == null ? null : new Vector3f(bend).normalize();
            pivot = pivot == null ? null : new Vector3f(pivot);
        }
    }

    public static final CgAssetCompiler<CgRig> COMPILER = (asset, deps) -> compile(asset.id(), asset.resolved());

    private final String id;
    @Nullable
    private final String model;
    private final CgRigAdapter adapter;
    private final String root, pelvis;
    private final List<Chain> chains;
    private final List<String> carried;

    private CgRig(String id, @Nullable String model, CgRigAdapter adapter, String root, String pelvis, List<Chain> chains,
                  List<String> carried) {
        this.id = Objects.requireNonNull(id, "id");
        this.model = model;
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.root = Objects.requireNonNull(root, "root");
        this.pelvis = Objects.requireNonNull(pelvis, "pelvis");
        this.chains = Collections.unmodifiableList(new ArrayList<>(chains));
        this.carried = Collections.unmodifiableList(new ArrayList<>(carried));
    }

    public static Builder builder(String id, CgRigAdapter adapter) {
        return new Builder(id, adapter);
    }

    /**
     * A builder holding everything this rig has: a detected rig given what its names cannot say.
     *
     * <pre>{@code
     * CgRig goku = CgRigs.detect(skeleton, CgRigAdapters.CRYSTALGRAPHICS).toBuilder().carried("AttachPoint_r").build();
     * }</pre>
     */
    public Builder toBuilder() {
        Builder b = new Builder(id, adapter);
        b.model = model;
        b.root = root;
        b.pelvis = pelvis;
        b.chains.addAll(chains);
        b.carried.addAll(carried);
        return b;
    }

    /** Registers {@link #COMPILER} as the rig kind's. */
    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.RIG, COMPILER);
    }

    public String id() {
        return id;
    }

    /** The model whose bones it names, as its file says; null for a rig made in code. */
    @Nullable
    public String model() {
        return model;
    }

    public CgRigAdapter adapter() {
        return adapter;
    }

    /** The bone everything hangs from, on the floor. */
    public String root() {
        return root;
    }

    /** The bone that carries the body: moved by a clip's hips, and lowered by foot placement. */
    public String pelvis() {
        return pelvis;
    }

    public List<Chain> chains() {
        return chains;
    }

    /** Animated bones that are not the body's (an effect's dummies): their motion is copied, not frozen. */
    public List<String> carried() {
        return carried;
    }

    /** The chain named {@code name}, or null. */
    @Nullable
    public Chain chain(String name) {
        for (Chain c : chains) if (c.name.equals(name)) return c;
        return null;
    }

    /**
     * The virtual bones a skeleton needs for this rig's goals: an IK root on the root, and each goal under it following
     * its chain's end. A goal the skeleton already has is left out.
     *
     * <pre>{@code
     * CgSkeletalMesh ready = mesh.withVirtualBones(rig.ikBones(mesh.skeleton()));
     * }</pre>
     */
    public List<CgVirtualBone> ikBones(CgSkeleton skeleton) {
        List<CgVirtualBone> out = new ArrayList<>();
        boolean any = false;
        for (Chain c : chains) any |= c.goal != null && skeleton.indexOf(c.goal) < 0;
        if (!any) return out;
        if (skeleton.indexOf(IK_ROOT) < 0) out.add(new CgVirtualBone(IK_ROOT, root, root));
        for (Chain c : chains) {
            if (c.goal != null && skeleton.indexOf(c.goal) < 0) out.add(new CgVirtualBone(c.goal, IK_ROOT, c.end));
        }
        return out;
    }

    /** The bone {@link #ikBones} puts the IK goals under. */
    public static final String IK_ROOT = "ik_root";

    /** Its file form: what {@link #compile} reads back equal. */
    public Map<String, Object> toTree() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (model != null) out.put("model", model);
        out.put("adapter", adapter.name());
        out.put("root", root);
        out.put("pelvis", pelvis);
        List<Object> list = new ArrayList<>();
        int i = 1;
        for (Chain c : chains) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "c" + i++);
            m.put("name", c.name);
            m.put("start", c.start);
            m.put("end", c.end);
            if (c.goal != null) m.put("goal", c.goal);
            if (c.bend != null) m.put("bend", List.of((double) c.bend.x(), (double) c.bend.y(), (double) c.bend.z()));
            if (c.rides != null) m.put("rides", c.rides);
            if (c.pivot != null) m.put("pivot", List.of((double) c.pivot.x(), (double) c.pivot.y(), (double) c.pivot.z()));
            list.add(m);
        }
        out.put("chains", list);
        if (!carried.isEmpty()) out.put("carried", new ArrayList<>(carried));
        return out;
    }

    /** {@code tree}, a {@code rig} asset's resolved tree. */
    public static CgRig compile(String id, Map<?, ?> tree) {
        String adapterName = String.valueOf(tree.get("adapter"));
        CgRigAdapter adapter = CgRigAdapters.get(adapterName);
        if (adapter == null) throw new IllegalArgumentException(id + ": no rig adapter " + adapterName);
        Builder b = builder(id, adapter);
        if (tree.get("model") != null) b.model(String.valueOf(tree.get("model")));
        if (tree.get("root") != null) b.root(String.valueOf(tree.get("root")));
        if (tree.get("pelvis") != null) b.pelvis(String.valueOf(tree.get("pelvis")));
        if (tree.get("chains") instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> m = (Map<?, ?>) o;
                Object goal = m.get("goal");
                b.chain(String.valueOf(m.get("name")), String.valueOf(m.get("start")), String.valueOf(m.get("end")),
                        goal == null ? null : String.valueOf(goal));
                if (m.get("bend") instanceof List<?> v) {
                    b.bend(String.valueOf(m.get("name")), number(v.get(0)), number(v.get(1)), number(v.get(2)));
                }
                if (m.get("rides") != null) b.rides(String.valueOf(m.get("name")), String.valueOf(m.get("rides")));
                if (m.get("pivot") instanceof List<?> v) {
                    b.pivot(String.valueOf(m.get("name")), number(v.get(0)), number(v.get(1)), number(v.get(2)));
                }
            }
        }
        if (tree.get("carried") instanceof List<?> list) for (Object o : list) b.carried(String.valueOf(o));
        return b.build();
    }

    private static float number(Object o) {
        return ((Number) o).floatValue();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof CgRig r && id.equals(r.id) && Objects.equals(model, r.model)
                && adapter.name().equals(r.adapter.name())
                && root.equals(r.root) && pelvis.equals(r.pelvis) && chains.equals(r.chains) && carried.equals(r.carried);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, root, pelvis, chains, carried);
    }

    @Override
    public String toString() {
        return "CgRig[" + id + ", " + chains.size() + " chains]";
    }

    public static final class Builder {
        private final String id;
        private final CgRigAdapter adapter;
        @Nullable
        private String model;
        private String root = "root", pelvis = "pelvis";
        private final List<Chain> chains = new ArrayList<>();
        private final List<String> carried = new ArrayList<>();

        private Builder(String id, CgRigAdapter adapter) {
            this.id = id;
            this.adapter = adapter;
        }

        /** The model whose bones it names: a file's {@code model}. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder root(String bone) {
            root = bone;
            return this;
        }

        public Builder pelvis(String bone) {
            pelvis = bone;
            return this;
        }

        /** A chain no IK reaches. */
        public Builder chain(String name, String start, String end) {
            return chain(name, start, end, null);
        }

        /** A chain whose end is placed by the IK bone {@code goal}. */
        public Builder chain(String name, String start, String end, @Nullable String goal) {
            for (Chain c : chains) if (c.name.equals(name)) throw new IllegalArgumentException(id + ": two chains named " + name);
            chains.add(new Chain(name, start, end, goal, null, null, null));
            return this;
        }

        /** Chain {@code name}'s middle joint bends along ({@code x}, {@code y}, {@code z}) in the model's space. */
        public Builder bend(String name, float x, float y, float z) {
            Chain c = find(name);
            chains.set(chains.indexOf(c), new Chain(c.name, c.start, c.end, c.goal, new Vector3f(x, y, z), c.rides, c.pivot));
            return this;
        }

        /** Chain {@code name}'s start moves with {@code bone} as if hung from it: an arm that hangs from the root rides the torso. */
        public Builder rides(String name, String bone) {
            Chain c = find(name);
            chains.set(chains.indexOf(c), new Chain(c.name, c.start, c.end, c.goal, c.bend, bone, c.pivot));
            return this;
        }

        /** Chain {@code name} turns about ({@code x}, {@code y}, {@code z}), in the model's space at rest: a torso pivoted at its neck turns at its hips. */
        public Builder pivot(String name, float x, float y, float z) {
            Chain c = find(name);
            chains.set(chains.indexOf(c), new Chain(c.name, c.start, c.end, c.goal, c.bend, c.rides, new Vector3f(x, y, z)));
            return this;
        }

        private Chain find(String name) {
            for (Chain c : chains) if (c.name.equals(name)) return c;
            throw new IllegalArgumentException(id + ": no chain " + name);
        }

        /** An animated bone that is not the body's (an effect's dummy): a retarget keeps its motion, adding it to a target that lacks it. */
        public Builder carried(String bone) {
            carried.add(bone);
            return this;
        }

        public CgRig build() {
            return new CgRig(id, model, adapter, root, pelvis, chains, carried);
        }
    }
}
