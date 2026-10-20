package com.crystalgraphics.object.value;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * How a value is written: a constant, or one of the maps that name a form ({@code docs/vfx/assets.md}
 * § Values).
 *
 * <p>Every input accepts every form unless its declaration narrows it. A declaration narrows only where the value
 * must be known before anything runs: a burst's count takes a constant or a range, a mesh input only data.</p>
 *
 * <pre>{@code
 * 0.5                                            CONSTANT
 * { range: [0.2, 0.4] }                          RANGE
 * { pick: [[-200, 0, 0], [200, 0, 0]] }          PICK
 * { curve: <curve or id>, by: age }              CURVE    (also { curves: [x, y, z] })
 * { gradient: <curve or id>, by: age }           GRADIENT
 * { array: [...], by: random }                   ARRAY
 * { bind: user.radius, scale: 0.8 }              BINDING
 * { fn: crystalgraphics:add, inputs: {...} }     FUNCTION
 * { mesh: <id> }, { texture: <id> }, { field: <id> }    DATA
 * { outward: 50, radial: { range: [0, 10] } }    STRUCT
 * }</pre>
 *
 * <ul>
 *   <li>A file never names a form: the map's one key does ({@link #keys()}). {@link #specName()} is the name a
 *       declaration's {@code forms:} list uses.</li>
 *   <li>A value in another unit wraps any form ({@code { unit: turns, range: [0.5, 1] }}); that wrapper is not a
 *       form of its own ({@link CgValue#unit()}).</li>
 * </ul>
 */
public enum CgValueForm {
    CONSTANT("constant"),
    RANGE("range", "range"),
    PICK("pick", "pick"),
    CURVE("curve", "curve", "curves"),
    GRADIENT("gradient", "gradient"),
    ARRAY("array", "array"),
    BINDING("binding", "bind"),
    FUNCTION("function", "fn"),
    DATA("data", "mesh", "texture", "field"),
    STRUCT("struct");

    /** Every form: what an input accepts when its declaration does not narrow it. */
    public static final Set<CgValueForm> ALL = Collections.unmodifiableSet(EnumSet.allOf(CgValueForm.class));

    private final String spec;
    private final String[] keys;

    CgValueForm(String spec, String... keys) {
        this.spec = spec;
        this.keys = keys;
    }

    /** The name in a declaration's {@code forms:} list ({@code binding}, {@code data}). */
    public String specName() {
        return spec;
    }

    /** The map keys that mark this form in a file; none for a constant or a struct. */
    public String[] keys() {
        return keys.clone();
    }

    /** The form a declaration's {@code forms:} entry names, or null if none does. */
    public static CgValueForm bySpecName(String name) {
        for (CgValueForm f : values()) {
            if (f.spec.equals(name)) return f;
        }
        return null;
    }

    /** The form whose map key {@code key} is ({@code bind} → {@link #BINDING}), or null for any other key. */
    public static CgValueForm byKey(String key) {
        for (CgValueForm f : values()) {
            for (String k : f.keys) {
                if (k.equals(key)) return f;
            }
        }
        return null;
    }
}
