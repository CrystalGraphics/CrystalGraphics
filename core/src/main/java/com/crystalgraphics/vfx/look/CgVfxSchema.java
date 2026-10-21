package com.crystalgraphics.vfx.look;

import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.layout.CgLayout;
import com.crystalgraphics.vfx.camera.CgCameraShake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A named set of parameters: what one effect, one kit or one look reads, each with a default in a unit. A look lays
 * out every schema it includes ({@link CgVfxLayout}): its effect's, each kit's, its own. It is a face of the object
 * model: each parameter declares a property of its {@link #type()}, and where each lives is that type's
 * {@link CgLayout}, as any declared block of parameters is laid out.
 *
 * <pre>{@code
 * public static final CgVfxSchema SCHEMA = CgVfxSchema.of("mymod:bolt");
 * public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 0.4f);
 * public static final CgVfxParam.Scalar REACH = SCHEMA.length("reach", RADIUS, 6f);       // RADIUS × 6 unless set
 * public static final CgVfxParam.Scalar LIFE = SCHEMA.time("life", 0.8f);
 * public static final CgVfxParam.Color CORE = SCHEMA.color("core", 0xFFFFFFFF);
 * public static final CgVfxParam.Color SHELL = SCHEMA.color("shell", 0xFF1858FF, 1.6f);     // rgb × 1.6: past white
 * public static final CgVfxParam.Palette BANDS = SCHEMA.palette("bands", 0xFFFFFFFF, 0xFF00FFFF, 0xFF0080E6);
 * public static final CgVfxParam.Curve GLOW = SCHEMA.curve("glow", CgKeyframes.start(0f, 0f).to(1f, 1f, LINEAR).build());
 * public static final CgVfxParam.Shake HIT = SCHEMA.shake("hit", CgCameraShakes.IMPACT);
 * }</pre>
 *
 * <ul>
 *   <li>Declare every parameter before the first look that includes it is built: building one freezes the schema,
 *       and a declaration after throws.</li>
 *   <li>A name is unique in its schema, and a schema's name among the schemas of a look.</li>
 *   <li>A link's source may be another schema's, as a kit's reach follows its effect's radius; a look including the one
 *       must include the other.</li>
 *   <li>{@link #type()} and {@link #layout()} freeze it, as a look does: a tool reads them once the looks are built.
 *       A palette declares one {@code colour} property an entry, {@code name[i]}.</li>
 * </ul>
 */
public final class CgVfxSchema {

    private static final AtomicInteger IDS = new AtomicInteger();

    final int id = IDS.getAndIncrement();
    private final String name;
    private final List<CgVfxParam> params = new ArrayList<>();
    private final List<CgNetField<?>> networked = new ArrayList<>();
    private final List<CgHitPart> parts = new ArrayList<>();
    private int floats, objects;
    private volatile boolean frozen;
    /** Its parameters as properties, declared as they are; registered, and laid out, when it freezes. */
    private final CgType.Builder declaration;
    private CgType type;
    private CgLayout layout;

    private CgVfxSchema(String name) {
        this.name = Objects.requireNonNull(name, "name");
        this.declaration = CgTypes.create().declare(typeId(name), CgTypeKind.STRUCT);
    }

    /** {@code name} as a type id: lower case, {@code vfx:} before a name with no namespace. */
    private static String typeId(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_:./]", "_");
        int colon = id.indexOf(':');
        if (colon < 0) return "vfx:" + id.replace("/", "_").replace(".", "_");
        return id.substring(0, colon).replaceAll("[^a-z0-9_]", "_") + ":" + id.substring(colon + 1).replace(":", "_");
    }

    /** A schema named {@code name}: by convention {@code namespace:path}, as {@code "crystalgraphics:wave"}. */
    public static CgVfxSchema of(String name) {
        return new CgVfxSchema(name);
    }

    public String name() {
        return name;
    }

    /** A number with no unit: a count, a share, a multiple. */
    public CgVfxParam.Scalar scalar(String name, float value) {
        return scalar(name, CgVfxUnit.NONE, value);
    }

    /** A number in {@code unit}. */
    public CgVfxParam.Scalar scalar(String name, CgVfxUnit unit, float value) {
        return add(new CgVfxParam.Scalar(this, unique(name), floats, unit, value, null, 0f));
    }

    /** A number in {@code unit} following {@code source × factor} until a look or an effect sets it. */
    public CgVfxParam.Scalar scalar(String name, CgVfxUnit unit, CgVfxParam.Scalar source, float factor) {
        Objects.requireNonNull(source, "source");
        return add(new CgVfxParam.Scalar(this, unique(name), floats, unit, 0f, source, factor));
    }

    /** A length in blocks. */
    public CgVfxParam.Scalar length(String name, float blocks) {
        return scalar(name, CgVfxUnit.LENGTH, blocks);
    }

    /** A length in blocks following {@code source × factor}. */
    public CgVfxParam.Scalar length(String name, CgVfxParam.Scalar source, float factor) {
        return scalar(name, CgVfxUnit.LENGTH, source, factor);
    }

    /** A time in seconds. */
    public CgVfxParam.Scalar time(String name, float seconds) {
        return scalar(name, CgVfxUnit.TIME, seconds);
    }

    /** A speed in blocks a second. */
    public CgVfxParam.Scalar speed(String name, float blocksPerSecond) {
        return scalar(name, CgVfxUnit.SPEED, blocksPerSecond);
    }

    /** A speed following {@code source × factor}. */
    public CgVfxParam.Scalar speed(String name, CgVfxParam.Scalar source, float factor) {
        return scalar(name, CgVfxUnit.SPEED, source, factor);
    }

    /** An angle in radians. */
    public CgVfxParam.Scalar angle(String name, float radians) {
        return scalar(name, CgVfxUnit.ANGLE, radians);
    }

    /** Times a second, or radians a second. */
    public CgVfxParam.Scalar rate(String name, float perSecond) {
        return scalar(name, CgVfxUnit.RATE, perSecond);
    }

    /** A colour, {@code 0xAARRGGBB}: its alpha is whatever its readers decide, usually a strength. */
    public CgVfxParam.Color color(String name, int argb) {
        return color(name, argb, 1f);
    }

    /** A colour, {@code 0xAARRGGBB}, its rgb times {@code intensity}: past white where above 1, which is what blooms. */
    public CgVfxParam.Color color(String name, int argb, float intensity) {
        return add(new CgVfxParam.Color(this, unique(name), floats, CgVfxParam.Color.red(argb) * intensity, CgVfxParam.Color.green(argb) * intensity,
                CgVfxParam.Color.blue(argb) * intensity, CgVfxParam.Color.alpha(argb)));
    }

    /** Three numbers, {@link CgVfxParam.Vector}: a direction, or a point from the effect's origin; NaN for none. */
    public CgVfxParam.Vector vector(String name, float x, float y, float z) {
        return add(new CgVfxParam.Vector(this, unique(name), floats, x, y, z));
    }

    /** Colours in a row, each {@code 0xAARRGGBB}. */
    public CgVfxParam.Palette palette(String name, int... argb) {
        if (argb.length == 0) throw new IllegalArgumentException("Palette '" + name + "' holds no colour");
        float[] rgba = new float[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            rgba[i * 4] = CgVfxParam.Color.red(argb[i]);
            rgba[i * 4 + 1] = CgVfxParam.Color.green(argb[i]);
            rgba[i * 4 + 2] = CgVfxParam.Color.blue(argb[i]);
            rgba[i * 4 + 3] = CgVfxParam.Color.alpha(argb[i]);
        }
        return add(new CgVfxParam.Palette(this, unique(name), floats, argb.length, rgba));
    }

    /** A value over time, read with {@code CgVfxValues.curve}. */
    public CgVfxParam.Curve curve(String name, CgKeyframes value) {
        return add(new CgVfxParam.Curve(this, unique(name), objects, Objects.requireNonNull(value, "value")));
    }

    /** A camera shake the effect plays, read with {@code CgVfxValues.shake}. */
    public CgVfxParam.Shake shake(String name, CgCameraShake value) {
        return add(new CgVfxParam.Shake(this, unique(name), objects, Objects.requireNonNull(value, "value")));
    }

    public List<CgVfxParam> params() {
        return Collections.unmodifiableList(params);
    }

    /** The parameter named {@code name}, or null. */
    public CgVfxParam param(String name) {
        for (CgVfxParam p : params) if (p.name.equals(name)) return p;
        return null;
    }

    /**
     * State an effect of this kind sends from the server's copy to its viewers, beyond its parameters: every cue playing
     * a look including this schema lists these fields.
     *
     * <pre>{@code
     * public static final CgNetField<Float> FIRED_AT = CgNetField.ofFloat("wave/fired_at").fallback(Float.NaN);
     * static { SCHEMA.networked(FIRED_AT); }
     * }</pre>
     */
    public CgVfxSchema networked(CgNetField<?>... fields) {
        checkOpen();
        Collections.addAll(networked, fields);
        return this;
    }

    public List<CgNetField<?>> networked() {
        return Collections.unmodifiableList(networked);
    }

    /**
     * Declares a part that strikes: each hit names its part, so a listener may hear one part alone.
     *
     * <pre>{@code
     * public static final CgHitPart PART_BOLT = SCHEMA.part("bolt");
     * bolt.onHit(Bolt.PART_BOLT, h -> damage(h.entity()));
     * }</pre>
     */
    public CgHitPart part(String name) {
        checkOpen();
        CgHitPart part = CgHitPart.of(name);
        parts.add(part);
        return part;
    }

    public List<CgHitPart> parts() {
        return Collections.unmodifiableList(parts);
    }

    /** Floats a full set of this schema's values takes. */
    public int floats() {
        return floats;
    }

    int objects() {
        return objects;
    }

    /** Whether a look has laid it out, after which nothing more is declared on it. */
    public boolean frozen() {
        return frozen;
    }

    /** Its parameters declared as a type: each one's property, in order, with its quantity and default. Freezes it. */
    public CgType type() {
        freeze();
        return type;
    }

    /** Where each parameter lives: its type's layout, each float and object at its parameter's offset. Freezes it. */
    public CgLayout layout() {
        freeze();
        return layout;
    }

    synchronized void freeze() {
        if (type != null) return;
        frozen = true;
        type = declaration.register();
        layout = CgLayout.of(type);
        for (CgVfxParam p : params) {
            CgLayout.Slot slot = layout.slot(p instanceof CgVfxParam.Palette ? p.name + "[0]" : p.name);
            boolean numeric = p.numeric();
            if (slot == null || (slot.store() == CgLayout.Store.FLOAT) != numeric || slot.offset() != p.local) {
                throw new IllegalStateException(name + "." + p.name + " is laid out at " + slot + ", not where it was declared ("
                        + p.local + ")");
            }
        }
    }

    /** Declares {@code param}'s property: what {@link #type()} holds and {@link #layout()} places. */
    private void declare(CgVfxParam param) {
        if (param instanceof CgVfxParam.Scalar s) {
            declaration.property(param.name, CgValueType.FLOAT).quantity(s.unit.quantity()).defaultValue((double) s.defaultValue());
        } else if (param instanceof CgVfxParam.Color c) {
            declaration.property(param.name, CgValueType.COLOUR).defaultValue(Arrays.asList((double) c.r, (double) c.g, (double) c.b, (double) c.a));
        } else if (param instanceof CgVfxParam.Vector v) {
            declaration.property(param.name, CgValueType.VEC3).defaultValue(Arrays.asList((double) v.x, (double) v.y, (double) v.z));
        } else if (param instanceof CgVfxParam.Palette p) {
            for (int i = 0; i < p.count; i++) {
                declaration.property(param.name + "[" + i + "]", CgValueType.COLOUR).defaultValue(Arrays.asList(
                        (double) p.values[i * 4], (double) p.values[i * 4 + 1], (double) p.values[i * 4 + 2], (double) p.values[i * 4 + 3]));
            }
        } else if (param instanceof CgVfxParam.Curve c) {
            declaration.property(param.name, CgValueType.CURVE).defaultValue(c.value);
        } else {
            declaration.property(param.name, CgValueType.VALUE).defaultValue(((CgVfxParam.Shake) param).value);
        }
    }

    private String unique(String name) {
        checkOpen();
        Objects.requireNonNull(name, "name");
        if (param(name) != null) throw new IllegalArgumentException("'" + name + "' is already declared on " + this.name);
        return name;
    }

    private void checkOpen() {
        if (frozen) {
            throw new IllegalStateException("Schema " + name + " is in a look already: declare its parameters, fields and"
                    + " parts before the first look including it is built (static field order)");
        }
    }

    private <P extends CgVfxParam> P add(P param) {
        declare(param);
        params.add(param);
        if (param.numeric()) floats += param.floats();
        else objects++;
        return param;
    }

    @Override
    public String toString() {
        return name;
    }
}
