package com.crystalgraphics.vfx.look;

import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.net.object.CgNetField;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Where each parameter of a look lives in its values: every schema the look includes, its effect's first, laid end to
 * end as flat floats (numbers, colours, palettes) and objects (curves, shakes). Looks of the same schemas share one.
 * Tools walk it; effects never see it.
 *
 * <pre>{@code
 * CgVfxLayout layout = look.layout();
 * for (CgVfxParam p : layout.params()) System.out.println(p + " at " + layout.offset(p));
 * }</pre>
 */
public final class CgVfxLayout {

    private static final Map<List<CgVfxSchema>, CgVfxLayout> INTERNED = new HashMap<>();

    private final List<CgVfxSchema> schemas;
    /** By a schema's id: where its floats and its objects start, -1 for a schema not in it. */
    private final int[] floatBase, objectBase;
    private final int floats, objectCount;
    private final List<CgVfxParam> params;
    private final List<CgNetField<?>> networked;
    private final List<CgHitPart> parts;
    private final float[] defaults;
    private final Object[] defaultObjects;
    /** The scalars that follow another by default: each one's offset, its source's, and the factor. */
    final int[] linkTo, linkFrom;
    final float[] linkFactor;

    private CgVfxLayout(List<CgVfxSchema> schemas) {
        this.schemas = Collections.unmodifiableList(new ArrayList<>(schemas));
        int maxId = 0;
        for (CgVfxSchema s : schemas) maxId = Math.max(maxId, s.id);
        floatBase = new int[maxId + 1];
        objectBase = new int[maxId + 1];
        Arrays.fill(floatBase, -1);
        Arrays.fill(objectBase, -1);
        int f = 0, o = 0;
        List<CgVfxParam> all = new ArrayList<>();
        List<CgNetField<?>> fields = new ArrayList<>();
        List<CgHitPart> hitParts = new ArrayList<>();
        for (CgVfxSchema s : schemas) {
            for (CgVfxSchema other : schemas) {
                if (other != s && other.name().equals(s.name()) && other.id < s.id) {
                    throw new IllegalArgumentException("Two schemas named " + s.name() + " in one look");
                }
            }
            s.freeze();
            floatBase[s.id] = f;
            objectBase[s.id] = o;
            f += s.floats();
            o += s.objects();
            all.addAll(s.params());
            fields.addAll(s.networked());
            hitParts.addAll(s.parts());
        }
        floats = f;
        objectCount = o;
        params = Collections.unmodifiableList(all);
        networked = Collections.unmodifiableList(fields);
        parts = Collections.unmodifiableList(hitParts);
        defaults = new float[f];
        defaultObjects = new Object[o];
        List<int[]> links = new ArrayList<>();
        List<Float> factors = new ArrayList<>();
        for (CgVfxParam p : all) {
            int at = offset(p);
            if (p instanceof CgVfxParam.Scalar) {
                CgVfxParam.Scalar s = (CgVfxParam.Scalar) p;
                defaults[at] = s.defaultValue();
                if (s.source != null) {
                    if (!includes(s.source)) {
                        throw new IllegalArgumentException(p + " follows " + s.source + ", which this look does not include");
                    }
                    links.add(new int[] {at, offset(s.source)});
                    factors.add(s.factor);
                }
            } else if (p instanceof CgVfxParam.Color) {
                CgVfxParam.Color c = (CgVfxParam.Color) p;
                defaults[at] = c.r;
                defaults[at + 1] = c.g;
                defaults[at + 2] = c.b;
                defaults[at + 3] = c.a;
            } else if (p instanceof CgVfxParam.Vector) {
                CgVfxParam.Vector v = (CgVfxParam.Vector) p;
                defaults[at] = v.x;
                defaults[at + 1] = v.y;
                defaults[at + 2] = v.z;
            } else if (p instanceof CgVfxParam.Palette) {
                System.arraycopy(((CgVfxParam.Palette) p).values, 0, defaults, at, p.floats());
            } else if (p instanceof CgVfxParam.Curve) {
                defaultObjects[at] = ((CgVfxParam.Curve) p).value;
            } else {
                defaultObjects[at] = ((CgVfxParam.Shake) p).value;
            }
        }
        linkTo = new int[links.size()];
        linkFrom = new int[links.size()];
        linkFactor = new float[links.size()];
        for (int i = 0; i < links.size(); i++) {
            linkTo[i] = links.get(i)[0];
            linkFrom[i] = links.get(i)[1];
            linkFactor[i] = factors.get(i);
        }
    }

    /** The layout of {@code schemas} in order, shared by every look of the same ones. */
    public static synchronized CgVfxLayout of(List<CgVfxSchema> schemas) {
        CgVfxLayout layout = INTERNED.get(schemas);
        if (layout == null) {
            layout = new CgVfxLayout(schemas);
            INTERNED.put(layout.schemas, layout);
        }
        return layout;
    }

    public List<CgVfxSchema> schemas() {
        return schemas;
    }

    /** The first schema: the effect's, whose parameters the effect class reads. */
    public CgVfxSchema primary() {
        return schemas.get(0);
    }

    public boolean includes(CgVfxSchema schema) {
        return schema.id < floatBase.length && floatBase[schema.id] >= 0;
    }

    public boolean includes(CgVfxParam param) {
        return includes(param.schema);
    }

    /** Where {@code param} starts among the floats (a number, colour or palette) or the objects (a curve or shake). */
    public int offset(CgVfxParam param) {
        int id = param.schema.id;
        int base = id < floatBase.length ? (param.numeric() ? floatBase[id] : objectBase[id]) : -1;
        if (base < 0) throw new IllegalArgumentException(param + " is not in a look of " + schemas);
        return base + param.local;
    }

    /** Every parameter, schema by schema. */
    public List<CgVfxParam> params() {
        return params;
    }

    /** The parameter {@link #qualifiedName} names {@code name}; null for none. */
    @Nullable
    public CgVfxParam param(String name) {
        for (CgVfxParam p : params) if (qualifiedName(p).equals(name)) return p;
        return null;
    }

    /**
     * {@code param}'s name in this layout, which tunings, overrides and cues use: its own for the effect's schema's;
     * after its schema's for another's, {@code :} and {@code /} as dots ({@code crystalgraphics.charge.radius}).
     */
    public String qualifiedName(CgVfxParam param) {
        return param.schema == primary() ? param.name : segments(param.schema.name()) + "." + param.name;
    }

    /** {@code crystalgraphics:wave/body} as {@code crystalgraphics.wave.body}, each segment a variable id's. */
    static String segments(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (String part : name.split("[:/]")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append('.');
            out.append(segment(part));
        }
        return out.toString();
    }

    /** One segment of a variable id: what is not a letter, digit or underscore as an underscore. */
    static String segment(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(Character.isLetterOrDigit(c) && c < 128 || c == '_' ? c : '_');
        }
        return out.length() == 0 ? "_" : out.toString();
    }

    /** Every schema's networked fields. */
    public List<CgNetField<?>> networked() {
        return networked;
    }

    /** Every schema's hit parts, schema by schema: a cue sends a part as its place here. */
    @Nullable
    public CgHitPart partAt(int index) {
        return index >= 0 && index < parts.size() ? parts.get(index) : null;
    }

    /** Where {@code part} is among {@link #partAt}'s; -1 for null or one no schema here declared. */
    public int indexOf(@Nullable CgHitPart part) {
        return part == null ? -1 : parts.indexOf(part);
    }

    public int floats() {
        return floats;
    }

    int objectCount() {
        return objectCount;
    }

    float[] defaults() {
        return defaults.clone();
    }

    Object[] defaultObjects() {
        return defaultObjects.clone();
    }

    @Override
    public String toString() {
        return "CgVfxLayout" + schemas;
    }
}
