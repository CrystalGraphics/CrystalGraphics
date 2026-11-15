package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * How one rig's animation plays on another: each target chain's source chain, how its bones follow, the poses that
 * line the two skeletons up, and the stack of steps a pose runs through. A clip made for Goku plays on a Minecraft
 * player through one.
 *
 * <pre>{@code
 * // the common case: chains mapped by name, the target's pose lined up with the source's, every step at its default
 * CgRetargeter r = CgRetargeter.auto(gokuSkeleton, gokuRig, playerSkeleton, playerRig);
 * CgRetargetProcessor p = new CgRetargetProcessor(r, gokuSkeleton, playerSkeleton);
 * p.run(gokuPose, playerPose);                                    // a source pose in, the target's out
 * }</pre>
 *
 * <pre>{@code
 * // a chain told how to follow, a step tuned
 * CgRetargeter tuned = r.toBuilder()
 *         .chain(CgRetargeter.ChainMap.of("spine", "spine").fk(CgRetargeter.Rotation.INTERPOLATED, CgRetargeter.Translation.NONE))
 *         .ops(CgRetargeter.Op.scaleSource(1f), CgRetargeter.Op.pelvisMotion(1f, 1f), CgRetargeter.Op.FK_CHAINS,
 *              CgRetargeter.Op.IK_CHAINS, CgRetargeter.Op.floor(1f))
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Retarget poses are rotations in each bone's own space added to its rest: {@link #auto} fills the target's so
 *       each mapped chain points where the source's does (an arms-down model under a source standing arms out).</li>
 *   <li>A target chain with no source keeps its retarget pose, turned with its parent.</li>
 *   <li>A setting with no implementation (a chain's {@code match_chain}, a {@code pin_bone} step) fails when a
 *       processor is made, naming it; nothing plays as something else.</li>
 * </ul>
 */
public final class CgRetargeter {

    /** How a target chain's bones take the source chain's rotations. */
    public enum Rotation { INTERPOLATED, ONE_TO_ONE, ONE_TO_ONE_REVERSED, MATCH_CHAIN, MATCH_SCALED_CHAIN, COPY_LOCAL, NONE }

    /** How a target chain's bones are placed. */
    public enum Translation { NONE, GLOBALLY_SCALED, ABSOLUTE, STRETCH_UNIFORM, STRETCH_NON_UNIFORM, ORIENT_AND_SCALE }

    /**
     * A target chain's source and how it follows it.
     *
     * @param target         the target rig's chain
     * @param source         the source rig's chain, or null to leave the target chain in its retarget pose
     * @param rotation       how its bones take the source's rotations
     * @param rotationAlpha  0 keeps the retarget pose, 1 the source's
     * @param translation    how its bones are placed
     * @param translationAlpha 0 keeps the retarget pose's places, 1 the mode's
     * @param ik             whether its goal is placed and solved (where both chains end in one)
     * @param blendToSource  0 places the goal by the target's limb length, 1 at the source's own place
     * @param extension      the goal's reach as a share of the solved limb's
     * @param staticOffset   a fixed offset added to the goal, in the model's space
     */
    public record ChainMap(String target, @Nullable String source, Rotation rotation, float rotationAlpha, Translation translation,
                           float translationAlpha, boolean ik, float blendToSource, float extension, Vector3fc staticOffset) {
        public ChainMap {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(translation, "translation");
            staticOffset = new Vector3f(staticOffset);
        }

        /** {@code target} following {@code source} at the defaults: interpolated rotations, places kept, IK on. */
        public static ChainMap of(String target, @Nullable String source) {
            return new ChainMap(target, source, Rotation.INTERPOLATED, 1f, Translation.NONE, 1f, true, 0f, 1f, new Vector3f());
        }

        public ChainMap fk(Rotation rotation, Translation translation) {
            return new ChainMap(target, source, rotation, rotationAlpha, translation, translationAlpha, ik, blendToSource, extension,
                    staticOffset);
        }

        public ChainMap ik(boolean on) {
            return new ChainMap(target, source, rotation, rotationAlpha, translation, translationAlpha, on, blendToSource, extension,
                    staticOffset);
        }

        public ChainMap ik(float blendToSource, float extension, Vector3fc staticOffset) {
            return new ChainMap(target, source, rotation, rotationAlpha, translation, translationAlpha, ik, blendToSource, extension,
                    staticOffset);
        }
    }

    /** One step of the stack, run in order. */
    public record Op(String kind, float a, float b, float c, float d) {
        public static final Op FK_CHAINS = new Op("fk_chains", 0f, 0f, 0f, 0f);
        public static final Op IK_CHAINS = new Op("ik_chains", 0f, 0f, 0f, 0f);

        /** Every source place scaled by {@code factor}, before anything reads it. */
        public static Op scaleSource(float factor) {
            return new Op("scale_source", factor, 0f, 0f, 0f);
        }

        /** The pelvis moved as the source's, its motion scaled by the height ratio, then across and up by these. */
        public static Op pelvisMotion(float scaleHorizontal, float scaleVertical) {
            return pelvisMotion(scaleHorizontal, scaleVertical, 1f, 1f);
        }

        public static Op pelvisMotion(float scaleHorizontal, float scaleVertical, float translationAlpha, float rotationAlpha) {
            return new Op("pelvis_motion", scaleHorizontal, scaleVertical, translationAlpha, rotationAlpha);
        }

        /** Feet near the floor held at the source's height above it, at {@code weight}. */
        public static Op floor(float weight) {
            return new Op("floor_constraint", weight, 0f, 0f, 0f);
        }

        public static final List<Op> DEFAULT = List.of(scaleSource(1f), pelvisMotion(1f, 1f), FK_CHAINS, IK_CHAINS, floor(1f));
    }

    /** A static socket the source game's moves name, placed on a target bone. */
    public record Socket(String name, String bone, Vector3fc location, Quaternionfc rotation, Vector3fc scale) {
        public Socket {
            location = new Vector3f(location);
            rotation = new Quaternionf(rotation);
            scale = new Vector3f(scale);
        }
    }

    private final String id;
    private final CgRig source, target;
    private final Map<String, Quaternionf> sourcePose, targetPose;
    private final List<ChainMap> chains;
    private final List<Op> ops;
    private final List<Socket> sockets;
    private final boolean scaleEffects;

    private CgRetargeter(Builder b) {
        id = b.id;
        source = Objects.requireNonNull(b.source, "source");
        target = Objects.requireNonNull(b.target, "target");
        sourcePose = Collections.unmodifiableMap(copy(b.sourcePose));
        targetPose = Collections.unmodifiableMap(copy(b.targetPose));
        chains = List.copyOf(b.chains);
        ops = List.copyOf(b.ops);
        sockets = List.copyOf(b.sockets);
        scaleEffects = b.scaleEffects;
    }

    private static Map<String, Quaternionf> copy(Map<String, Quaternionf> in) {
        Map<String, Quaternionf> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, new Quaternionf(v)));
        return out;
    }

    public static Builder builder(String id, CgRig source, CgRig target) {
        return new Builder(id, source, target);
    }

    /**
     * The retargeter an editor makes before anyone touches it: every target chain mapped to the source chain of the same
     * name, else the closest name; the default steps; and the target's retarget pose turned so each mapped chain points
     * as its source does.
     */
    public static CgRetargeter auto(CgSkeleton sourceSkeleton, CgRig source, CgSkeleton targetSkeleton, CgRig target) {
        Builder b = builder(source.id() + "->" + target.id(), source, target);
        for (CgRig.Chain t : target.chains()) {
            String match = closest(t.name(), source.chains());
            ChainMap map = ChainMap.of(t.name(), match);
            CgRig.Chain s = match == null ? null : source.chain(match);
            if (s == null || s.goal() == null || t.goal() == null) map = map.ik(false);
            b.chain(map);
        }
        CgRetargeter mapped = b.build();
        Map<String, Quaternionf> aligned = CgRetargetPoses.alignTarget(mapped, sourceSkeleton, targetSkeleton);
        return mapped.toBuilder().targetPose(aligned).build();
    }

    /** The exact name, else the closest by edit distance with a score above 0.2, else null. */
    @Nullable
    static String closest(String target, List<CgRig.Chain> sources) {
        for (CgRig.Chain s : sources) if (s.name().equals(target)) return s.name();
        String t = target.toLowerCase(Locale.ROOT), best = null;
        float bestScore = 0.2f;
        for (CgRig.Chain s : sources) {
            String n = s.name().toLowerCase(Locale.ROOT);
            float worst = Math.max(1f, t.length() + n.length());
            float score = 1f - editDistance(t, n) / worst;
            if (score > bestScore) {
                bestScore = score;
                best = s.name();
            }
        }
        return best;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    public String id() {
        return id;
    }

    public CgRig source() {
        return source;
    }

    public CgRig target() {
        return target;
    }

    /** The source's retarget pose: bone-space rotations added to its rest, by bone. */
    public Map<String, Quaternionf> sourcePose() {
        return sourcePose;
    }

    /** The target's retarget pose: bone-space rotations added to its rest, by bone. */
    public Map<String, Quaternionf> targetPose() {
        return targetPose;
    }

    public List<ChainMap> chains() {
        return chains;
    }

    /** The target chain {@code target}'s mapping, or null. */
    @Nullable
    public ChainMap chain(String target) {
        for (ChainMap c : chains) if (c.target.equals(target)) return c;
        return null;
    }

    public List<Op> ops() {
        return ops;
    }

    /** The first step of {@code kind}, or null. */
    @Nullable
    public Op op(String kind) {
        for (Op o : ops) if (o.kind.equals(kind)) return o;
        return null;
    }

    public List<Socket> sockets() {
        return sockets;
    }

    /** Whether an effect played on the target takes its height ratio; off, it keeps its authored size. */
    public boolean scaleEffects() {
        return scaleEffects;
    }

    public Builder toBuilder() {
        Builder b = new Builder(id, source, target);
        b.sourcePose.putAll(sourcePose);
        b.targetPose.putAll(targetPose);
        b.chains.addAll(chains);
        b.ops.clear();
        b.ops.addAll(ops);
        b.sockets.addAll(sockets);
        b.scaleEffects = scaleEffects;
        return b;
    }

    /** Compiles a {@code retargeters/} asset, its rigs read from its hard references. */
    public static final CgAssetCompiler<CgRetargeter> COMPILER = (asset, deps) -> compile(asset.id(), asset.resolved(), deps.units(),
            id -> (CgRig) deps.definition(CgBodyKinds.RIG, id));

    /** Registers {@link #COMPILER} as the retargeter kind's. */
    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.RETARGETER, COMPILER);
    }

    /**
     * Its file form, in the host's units: what {@link #compile} reads back the same. Pose rotations are written as XYZ
     * angles in radians, socket rotations as quaternions.
     */
    public Map<String, Object> toTree() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source.id());
        out.put("target", target.id());
        Map<String, Object> poses = new LinkedHashMap<>();
        poses.put("source", poseTree(sourcePose));
        poses.put("target", poseTree(targetPose));
        out.put("poses", poses);
        List<Object> list = new ArrayList<>();
        int i = 1;
        for (ChainMap c : chains) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "m" + i++);
            m.put("target", c.target);
            if (c.source != null) m.put("source", c.source);
            Map<String, Object> fk = new LinkedHashMap<>();
            fk.put("rotation", c.rotation.name().toLowerCase(Locale.ROOT));
            fk.put("rotation_alpha", (double) c.rotationAlpha);
            fk.put("translation", c.translation.name().toLowerCase(Locale.ROOT));
            fk.put("translation_alpha", (double) c.translationAlpha);
            m.put("fk", fk);
            Map<String, Object> ik = new LinkedHashMap<>();
            ik.put("enabled", c.ik);
            ik.put("blend_to_source", (double) c.blendToSource);
            ik.put("extension", (double) c.extension);
            ik.put("static_offset", vec(c.staticOffset));
            m.put("ik", ik);
            list.add(m);
        }
        out.put("chains", list);
        List<Object> opList = new ArrayList<>();
        i = 1;
        for (Op op : ops) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "o" + i++);
            m.put("op", op.kind);
            switch (op.kind) {
                case "scale_source" -> m.put("factor", (double) op.a);
                case "pelvis_motion" -> {
                    m.put("scale_horizontal", (double) op.a);
                    m.put("scale_vertical", (double) op.b);
                    m.put("translation_alpha", (double) op.c);
                    m.put("rotation_alpha", (double) op.d);
                }
                case "floor_constraint" -> m.put("weight", (double) op.a);
                default -> {
                }
            }
            opList.add(m);
        }
        out.put("ops", opList);
        if (!sockets.isEmpty()) {
            List<Object> socketList = new ArrayList<>();
            i = 1;
            for (Socket so : sockets) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", "s" + i++);
                m.put("name", so.name);
                m.put("bone", so.bone);
                m.put("location", vec(so.location));
                m.put("rotation", List.of((double) so.rotation.x(), (double) so.rotation.y(), (double) so.rotation.z(),
                        (double) so.rotation.w()));
                m.put("scale", vec(so.scale));
                socketList.add(m);
            }
            out.put("sockets", socketList);
        }
        if (scaleEffects) out.put("scale_effects", true);
        return out;
    }

    private static Map<String, Object> poseTree(Map<String, Quaternionf> pose) {
        Map<String, Object> rotations = new LinkedHashMap<>();
        pose.forEach((bone, q) -> rotations.put(bone, vec(q.getEulerAnglesXYZ(new Vector3f()))));
        Map<String, Object> out = new LinkedHashMap<>();
        if (!rotations.isEmpty()) out.put("rotations", rotations);
        return out;
    }

    private static List<Object> vec(Vector3fc v) {
        return List.of((double) v.x(), (double) v.y(), (double) v.z());
    }

    /**
     * {@code tree}, a {@code retargeters/} asset's resolved tree, written in {@code units}; {@code rigs} its rigs by id.
     * A pose's rotations are three angles in the asset's convention (Unreal's rotator for an Unreal kit), XYZ in
     * radians where it names none.
     *
     * @throws IllegalArgumentException for a rig {@code rigs} lacks, an unknown mode or step, or a setting nothing
     *                                  implements yet ({@code root_offset}, {@code static_rotation_offset},
     *                                  {@code scale_vertical}), by name
     */
    public static CgRetargeter compile(String id, Map<?, ?> tree, CgUnits units, Function<String, CgRig> rigs) {
        CgRig source = rig(id, tree.get("source"), rigs), target = rig(id, tree.get("target"), rigs);
        Builder b = builder(id, source, target);
        CgUnits angles = units.rotation().equals("quaternion")
                ? new CgUnits(units.metres(), units.seconds(), units.axes(), units.radians(), "euler:xyz:intrinsic") : units;
        if (tree.get("poses") instanceof Map<?, ?> poses) {
            b.sourcePose(pose(id, poses.get("source"), angles));
            b.targetPose(pose(id, poses.get("target"), angles));
        }
        if (tree.get("chains") instanceof List<?> list) {
            for (Object o : list) b.chain(chainMap(id, (Map<?, ?>) o, units));
        }
        if (tree.get("ops") instanceof List<?> list) {
            List<Op> stack = new ArrayList<>();
            for (Object o : list) stack.add(op(id, (Map<?, ?>) o));
            b.ops(stack.toArray(new Op[0]));
        }
        if (tree.get("sockets") instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> m = (Map<?, ?>) o;
                Vector3f location = new Vector3f(), scale = new Vector3f(1f);
                if (m.get("location") instanceof List<?> v) {
                    location.set(number(v.get(0), 0f), number(v.get(1), 0f), number(v.get(2), 0f)).mul((float) units.metres());
                }
                if (m.get("scale") instanceof List<?> v) scale.set(number(v.get(0), 1f), number(v.get(1), 1f), number(v.get(2), 1f));
                b.socket(new Socket(String.valueOf(m.get("name")), String.valueOf(m.get("bone")), location,
                        units.rotationToHost(m.get("rotation"), new Quaternionf()), scale));
            }
        }
        b.scaleEffects(Boolean.TRUE.equals(tree.get("scale_effects")));
        return b.build();
    }

    private static ChainMap chainMap(String id, Map<?, ?> m, CgUnits units) {
        Object src = m.get("source");
        ChainMap map = ChainMap.of(String.valueOf(m.get("target")), src == null ? null : String.valueOf(src));
        if (m.get("fk") instanceof Map<?, ?> fk) {
            map = new ChainMap(map.target, map.source, rotation(id, fk.get("rotation")), number(fk.get("rotation_alpha"), 1f),
                    translation(id, fk.get("translation")), number(fk.get("translation_alpha"), 1f), map.ik, map.blendToSource,
                    map.extension, map.staticOffset);
        }
        if (m.get("ik") instanceof Map<?, ?> ik) {
            if (ik.get("static_rotation_offset") instanceof List<?> r && (number(r.get(0), 0f) != 0f || number(r.get(1), 0f) != 0f
                    || number(r.get(2), 0f) != 0f)) {
                throw new IllegalArgumentException(id + ": chain " + map.target + "'s ik.static_rotation_offset is not implemented");
            }
            if (ik.get("scale_vertical") != null && number(ik.get("scale_vertical"), 1f) != 1f) {
                throw new IllegalArgumentException(id + ": chain " + map.target + "'s ik.scale_vertical is not implemented");
            }
            Vector3f offset = new Vector3f();
            if (ik.get("static_offset") instanceof List<?> v) {
                offset.set(number(v.get(0), 0f), number(v.get(1), 0f), number(v.get(2), 0f)).mul((float) units.metres());
            }
            map = new ChainMap(map.target, map.source, map.rotation, map.rotationAlpha, map.translation, map.translationAlpha,
                    !Boolean.FALSE.equals(ik.get("enabled")), number(ik.get("blend_to_source"), 0f), number(ik.get("extension"), 1f),
                    offset);
        }
        return map;
    }

    private static Op op(String id, Map<?, ?> m) {
        String kind = String.valueOf(m.get("op"));
        return switch (kind) {
            case "scale_source" -> Op.scaleSource(number(m.get("factor"), 1f));
            case "pelvis_motion" -> Op.pelvisMotion(number(m.get("scale_horizontal"), 1f), number(m.get("scale_vertical"), 1f),
                    number(m.get("translation_alpha"), 1f), number(m.get("rotation_alpha"), 1f));
            case "fk_chains" -> Op.FK_CHAINS;
            case "ik_chains" -> Op.IK_CHAINS;
            case "floor_constraint" -> Op.floor(number(m.get("weight"), 1f));
            default -> throw new IllegalArgumentException(id + ": step " + kind + " is not implemented");
        };
    }

    private static CgRig rig(String id, Object ref, Function<String, CgRig> rigs) {
        CgRig rig = ref == null ? null : rigs.apply(String.valueOf(ref));
        if (rig == null) throw new IllegalArgumentException(id + ": no rig " + ref);
        return rig;
    }

    private static Map<String, Quaternionf> pose(String id, Object tree, CgUnits units) {
        Map<String, Quaternionf> out = new LinkedHashMap<>();
        if (!(tree instanceof Map<?, ?> m)) return out;
        if (m.get("root_offset") instanceof List<?> v && (number(v.get(0), 0f) != 0f || number(v.get(1), 0f) != 0f
                || number(v.get(2), 0f) != 0f)) {
            throw new IllegalArgumentException(id + ": a retarget pose's root_offset is not implemented");
        }
        if (m.get("rotations") instanceof Map<?, ?> rotations) {
            float k = (float) units.radians();
            rotations.forEach((bone, v) -> {
                List<?> a = (List<?>) v;
                out.put(String.valueOf(bone), units.rotationToHost(number(a.get(0), 0f) * k, number(a.get(1), 0f) * k,
                        number(a.get(2), 0f) * k, new Quaternionf()));
            });
        }
        return out;
    }

    private static Rotation rotation(String id, Object v) {
        try {
            return v == null ? Rotation.INTERPOLATED : Rotation.valueOf(String.valueOf(v).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(id + ": no fk rotation " + v);
        }
    }

    private static Translation translation(String id, Object v) {
        try {
            return v == null ? Translation.NONE : Translation.valueOf(String.valueOf(v).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(id + ": no fk translation " + v);
        }
    }

    private static float number(Object o, float otherwise) {
        return o instanceof Number n ? n.floatValue() : otherwise;
    }

    /** A hash of everything that changes what it makes: a baked clip's cache key carries it. */
    public long contentHash() {
        long h = 1469598103934665603L;
        h = mix(h, source.id().hashCode());
        h = mix(h, target.id().hashCode());
        h = mix(h, source.chains().hashCode());
        h = mix(h, target.chains().hashCode());
        for (Map.Entry<String, Quaternionf> e : sourcePose.entrySet()) h = mix(mix(h, e.getKey().hashCode()), quatHash(e.getValue()));
        for (Map.Entry<String, Quaternionf> e : targetPose.entrySet()) h = mix(mix(h, e.getKey().hashCode()), quatHash(e.getValue()));
        h = mix(h, chains.hashCode());
        h = mix(h, ops.hashCode());
        return h;
    }

    private static int quatHash(Quaternionfc q) {
        return Objects.hash(q.x(), q.y(), q.z(), q.w());
    }

    private static long mix(long h, int v) {
        return (h ^ v) * 1099511628211L;
    }

    public static final class Builder {
        private final String id;
        private final CgRig source, target;
        private final Map<String, Quaternionf> sourcePose = new LinkedHashMap<>(), targetPose = new LinkedHashMap<>();
        private final List<ChainMap> chains = new ArrayList<>();
        private final List<Op> ops = new ArrayList<>(Op.DEFAULT);
        private final List<Socket> sockets = new ArrayList<>();
        private boolean scaleEffects;

        private Builder(String id, CgRig source, CgRig target) {
            this.id = id;
            this.source = source;
            this.target = target;
        }

        /** {@code target}'s mapping, replacing any for the same target chain. */
        public Builder chain(ChainMap map) {
            chains.removeIf(c -> c.target.equals(map.target));
            chains.add(map);
            return this;
        }

        public Builder sourcePose(Map<String, Quaternionf> rotations) {
            sourcePose.clear();
            sourcePose.putAll(rotations);
            return this;
        }

        public Builder targetPose(Map<String, Quaternionf> rotations) {
            targetPose.clear();
            targetPose.putAll(rotations);
            return this;
        }

        /** Bone {@code bone}'s rotation added to its rest in the target's retarget pose. */
        public Builder targetRotation(String bone, Quaternionfc rotation) {
            targetPose.put(bone, new Quaternionf(rotation));
            return this;
        }

        public Builder ops(Op... stack) {
            ops.clear();
            ops.addAll(List.of(stack));
            return this;
        }

        public Builder socket(Socket socket) {
            sockets.add(socket);
            return this;
        }

        public Builder scaleEffects(boolean on) {
            scaleEffects = on;
            return this;
        }

        public CgRetargeter build() {
            return new CgRetargeter(this);
        }
    }
}
