package com.crystalgraphics.object.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgValueType;

/**
 * A block of parameters laid out flat: each declared property at an offset in a float, int or object array, in
 * declaration order, as {@code FNiagaraParameterStore} lays out a system's parameters
 * ({@code NiagaraParameterStore.h}). A function of the declaration alone, never of values, so two definitions of one
 * type share a layout and a pool's shape.
 *
 * <pre>{@code
 * CgLayout layout = CgLayout.of(type);                       // once, when the definition compiles
 * CgLayout.Slot radius = layout.bind(CgParam.ofFloat("radius"));   // a missing or mistyped name throws here
 * CgLayoutValues values = layout.values();                   // the declaration's defaults
 * values.setFloat(radius, 2f);
 * }</pre>
 *
 * <ul>
 *   <li>Floats hold {@code float} and every vector ({@code vec2} to {@code colour}, a {@code position} or
 *       {@code rotation} as its numbers); ints hold {@code int}, {@code bool} and an enum's index; objects the rest.</li>
 *   <li>Slots are in declaration order, never hash order: server and client lay out alike.</li>
 * </ul>
 */
public final class CgLayout {

    /** Which array a slot lives in. */
    public enum Store { FLOAT, INT, OBJECT }

    /**
     * One property's place.
     *
     * @param name     the property's name
     * @param property its declaration
     * @param store    the array it lives in
     * @param offset   its first element there
     * @param width    how many elements it takes: 1, or a vector's numbers
     */
    public record Slot(String name, CgProperty property, Store store, int offset, int width) {

        /** Where a value of this slot is read from by a kernel: its declared form's class. */
        public CgFormClass formClass() {
            if (property.has(CgPropertyFlag.PER_INSTANCE)) return CgFormClass.INSTANCE_LANE;
            return store == Store.OBJECT ? CgFormClass.WORLD : CgFormClass.ROW;
        }
    }

    private final String id;
    private final List<Slot> slots;
    private final Map<String, Slot> byName;
    private final int floats, ints, objects;

    private CgLayout(String id, List<CgProperty> properties) {
        this.id = id;
        List<Slot> list = new ArrayList<>(properties.size());
        Map<String, Slot> names = new LinkedHashMap<>();
        int f = 0, i = 0, o = 0;
        for (CgProperty p : properties) {
            Slot s;
            int width = width(p.type());
            if (width > 0) {
                s = new Slot(p.name(), p, Store.FLOAT, f, width);
                f += width;
            } else if (isInt(p.type())) {
                s = new Slot(p.name(), p, Store.INT, i++, 1);
            } else {
                s = new Slot(p.name(), p, Store.OBJECT, o++, 1);
            }
            list.add(s);
            names.put(s.name(), s);
        }
        this.slots = Collections.unmodifiableList(list);
        this.byName = names;
        this.floats = f;
        this.ints = i;
        this.objects = o;
    }

    /** The layout of {@code type}'s properties. */
    public static CgLayout of(CgType type) {
        return new CgLayout(type.id(), type.properties());
    }

    /** The layout of {@code properties}, in their order; {@code id} names it in messages. */
    public static CgLayout of(String id, List<CgProperty> properties) {
        return new CgLayout(id, properties);
    }

    /** The floats a value of {@code type} takes, 0 where it is not held as floats. */
    static int width(CgValueType type) {
        switch (type.kind()) {
            case FLOAT: return 1;
            case VEC2: return 2;
            case VEC3: case POSITION: return 3;
            case VEC4: case COLOUR: case ROTATION: return 4;
            default: return 0;
        }
    }

    static boolean isInt(CgValueType type) {
        CgValueType.Kind k = type.kind();
        return k == CgValueType.Kind.INT || k == CgValueType.Kind.BOOL || k == CgValueType.Kind.ENUM;
    }

    /** What it was laid out from: the type's id. */
    public String id() {
        return id;
    }

    /** Every slot, in declaration order. */
    public List<Slot> slots() {
        return slots;
    }

    /** The slot named {@code name}, or null. */
    public Slot slot(String name) {
        return byName.get(name);
    }

    /** {@code param}'s slot: throws, naming the layout and the parameter, when the name is missing or its type differs. */
    public Slot bind(CgParam<?> param) {
        Slot s = byName.get(param.name());
        if (s == null) throw new IllegalArgumentException(id + " has no parameter '" + param.name() + "' for " + param);
        if (!param.type().accepts(s.property().type())) {
            throw new IllegalArgumentException(id + "." + param.name() + " is " + s.property().type() + ", not " + param);
        }
        return s;
    }

    public int floats() {
        return floats;
    }

    public int ints() {
        return ints;
    }

    public int objects() {
        return objects;
    }

    /** A new set of values over it, each at its declaration's default. */
    public CgLayoutValues values() {
        return new CgLayoutValues(this);
    }

    @Override
    public String toString() {
        return "CgLayout[" + id + ": " + floats + " floats, " + ints + " ints, " + objects + " objects]";
    }
}
