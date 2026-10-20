package com.crystalgraphics.object;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Every declared type, by id: the engine's 20 kinds and their structs, dispatch families and their members, and a
 * mod's own. What Unreal's {@code UClass} and {@code UScriptStruct} registries are to its
 * reflection.
 *
 * <pre>{@code
 * // at init, both sides: declare, then register
 * public static final CgType RING = CgTypes.struct("mymod:ring")
 *         .property("radius", CgValueType.FLOAT).quantity(CgQuantity.LENGTH)
 *         .register();
 *
 * CgTypes.get("crystalgraphics:system");         // the system kind
 * CgTypes.kinds();                               // every asset kind, in registration order
 * }</pre>
 *
 * <p>The engine's init ends at the first asset a pipeline requests: every native kind is declared then
 * ({@code CgAssetKinds.register()}) and the registry {@linkplain #freeze() freezes}, so a class compiled against a
 * family never meets a member that arrived after it.</p>
 *
 * <ul>
 *   <li>Register at init on both sides: a dedicated server registers every type too, since layouts must agree.</li>
 *   <li>An id is {@code namespace:path}, lower-case; a second type of one id throws, naming it.</li>
 *   <li>Once frozen, a native declaration ({@link #kind}, {@link #struct}, {@link #element}, {@link #dispatch}) or
 *       a family's new member throws, naming the type and the class that declared it late. Data types declared by
 *       assets ({@link #declare}) still register, and freeze one by one as definitions compile against them.</li>
 * </ul>
 */
public final class CgTypes {

    private static final Pattern ID = Pattern.compile("^[a-z0-9_]+:[a-z0-9_./]+$");
    private static final CgTypes GLOBAL = new CgTypes();

    // ── Units: every kind's `units`, declared here since compiling converts by it ──

    private static final CgType METRES = struct("crystalgraphics:units.metres")
            .property("metres", CgValueType.FLOAT).required()
            .register();

    private static final CgType AXES = struct("crystalgraphics:units.axes")
            .property("up", CgValueType.STRING).required()
            .property("forward", CgValueType.STRING).required()
            .property("handedness", CgValueType.enumOf("left", "right")).required()
            .register();

    private static final CgType ROTATION_ORDER = struct("crystalgraphics:units.rotation")
            .property("order", CgValueType.STRING).required()
            .property("intrinsic", CgValueType.BOOL).defaultValue(true)
            .register();

    private static final CgType TIME = struct("crystalgraphics:units.time")
            .property("seconds", CgValueType.BOOL).defaultValue(true)
            .property("frames", CgValueType.FLOAT)
            .register();

    /**
     * What an asset's numbers are in: its {@code units:} block, overriding its namespace's {@code units.yaml}.
     * A child's units are its parent's.
     *
     * <pre>{@code
     * units: { length: cm, axes: unreal, angles: degrees, rotation: unreal_rotator, time: { seconds: true, frames: 30 } }
     * units: { length: { metres: 0.0254 }, axes: { up: +z, forward: +x, handedness: left } }
     * }</pre>
     */
    public static final CgType UNITS = struct("crystalgraphics:units")
            .property("length", CgValueType.oneOf(CgValueType.enumOf("m", "cm", "mm", "in", "ft", "px"), CgValueType.struct(METRES)))
            .property("axes", CgValueType.oneOf(CgValueType.enumOf("unreal", "unity", "gltf", "blender", "minecraft", "cc2"),
                    CgValueType.struct(AXES)))
            .property("angles", CgValueType.enumOf("degrees", "radians", "turns"))
            .property("rotation", CgValueType.oneOf(CgValueType.enumOf("unreal_rotator", "unity_euler", "blender_xyz",
                    "quaternion", "cc2"), CgValueType.struct(ROTATION_ORDER)))
            .property("time", CgValueType.struct(TIME))
            .property("colour", CgValueType.enumOf("linear", "srgb"))
            .register();

    private final Map<String, CgType> types = new ConcurrentHashMap<>();
    private final List<CgType> order = new ArrayList<>();
    private volatile boolean frozen;

    /** A registry apart from the engine's: an editor preview's data types, a test's. */
    public static CgTypes create() {
        return new CgTypes();
    }

    /** The engine's registry, where every native type lives. */
    public static CgTypes global() {
        return GLOBAL;
    }

    // ── Declaring, in the engine's registry ───────────────────────────────────────────

    /** An asset kind: one file each, in its folder, with a version. */
    public static CgType.Builder kind(String id) {
        return GLOBAL.declareNative(id, CgTypeKind.KIND);
    }

    /** A struct: a value with named fields. */
    public static CgType.Builder struct(String id) {
        return GLOBAL.declareNative(id, CgTypeKind.STRUCT);
    }

    /** An id list's element: carries an {@code id}, merged by it. */
    public static CgType.Builder element(String id) {
        return GLOBAL.declareNative(id, CgTypeKind.ELEMENT);
    }

    /**
     * A dispatch family: members chosen by {@code key}'s value, their fields beside the key
     * ({@code { kind: perlin, amplitude: 2 }}). Add members with {@link CgType#member(String, CgType)}.
     */
    public static CgType.Builder dispatch(String id, String key) {
        return GLOBAL.declareNative(id, CgTypeKind.DISPATCH).dispatchKey(key, true);
    }

    /** The engine's type of {@code id}, or null. */
    public static CgType get(String id) {
        return GLOBAL.type(id);
    }

    /** Every asset kind the engine's registry holds, in registration order. */
    public static List<CgType> kinds() {
        List<CgType> out = new ArrayList<>();
        for (CgType t : GLOBAL.all()) {
            if (t.kind() == CgTypeKind.KIND) out.add(t);
        }
        return out;
    }

    /**
     * Ends the engine's init: every type registered so far freezes, so no family takes a member after it. The asset
     * pipeline calls it at its first request.
     */
    public static void freeze() {
        GLOBAL.freezeAll();
    }

    // ── A registry ─────────────────────────────────────────────────────────────────────

    /** A native declaration: refused once the engine's init is over. */
    private CgType.Builder declareNative(String id, CgTypeKind kind) {
        if (frozen) throw new IllegalStateException(id + " is declared by " + registrant() + " after the engine's init"
                + " froze its types: declare it at init, before the first asset loads");
        return declare(id, kind);
    }

    /** The class outside this package whose call is on the stack: who is declaring. Only to throw. */
    static String registrant() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if (!e.getClassName().startsWith("com.crystalgraphics.object.")) return e.getClassName();
        }
        return "an unknown class";
    }

    /** Starts declaring a type in this registry. */
    public CgType.Builder declare(String id, CgTypeKind kind) {
        return new CgType.Builder(this, id, kind);
    }

    /** The type of {@code id}, or null. */
    public CgType type(String id) {
        return types.get(id);
    }

    /** Every type, in registration order. */
    public synchronized List<CgType> all() {
        return Collections.unmodifiableList(new ArrayList<>(order));
    }

    /** Whether {@link #freeze()} has run on this registry. */
    public boolean isFrozen() {
        return frozen;
    }

    synchronized void freezeAll() {
        frozen = true;
        for (CgType t : order) t.freeze();
    }

    synchronized CgType add(CgType type) {
        if (types.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("a type '" + type.id() + "' is already registered");
        }
        order.add(type);
        return type;
    }

    static String checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("a type id is namespace:path, lower-case: '" + id + "'");
        }
        return id;
    }
}
