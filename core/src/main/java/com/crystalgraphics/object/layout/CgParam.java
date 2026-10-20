package com.crystalgraphics.object.layout;

import java.util.Locale;
import java.util.Objects;

import com.crystalgraphics.object.CgValueType;

/**
 * A typed handle on a parameter, by name: a native effect's contract with the definitions it plays, resolved to a slot
 * once, when the definition compiles ({@link CgLayout#bind}). {@code T} is the Java type the handle reads.
 *
 * <pre>{@code
 * public static final CgParam<Float> RADIUS = CgParam.ofFloat("radius");
 *
 * CgLayout.Slot radius = layout.bind(RADIUS);      // compile: "...has no parameter 'radius'" or "...is vec3, not float"
 * float r = values.getFloat(radius);                // play: by offset, no lookup
 * }</pre>
 *
 * @param name the parameter's name in its type
 * @param type what it must be declared as
 * @param <T>  what it reads as
 */
public record CgParam<T>(String name, Type type) {

    /** What a handle reads, and the declarations it takes. */
    public enum Type {
        FLOAT, INT, BOOL, ENUM, VEC2, VEC3, VEC4, COLOUR, OBJECT;

        /** True where a property of {@code declared} reads as this. */
        public boolean accepts(CgValueType declared) {
            CgValueType.Kind k = declared.kind();
            switch (this) {
                case FLOAT: return k == CgValueType.Kind.FLOAT;
                case INT: return k == CgValueType.Kind.INT;
                case BOOL: return k == CgValueType.Kind.BOOL;
                case ENUM: return k == CgValueType.Kind.ENUM;
                case VEC2: return k == CgValueType.Kind.VEC2;
                case VEC3: return k == CgValueType.Kind.VEC3 || k == CgValueType.Kind.POSITION;
                case VEC4: return k == CgValueType.Kind.VEC4 || k == CgValueType.Kind.ROTATION;
                case COLOUR: return k == CgValueType.Kind.COLOUR;
                default: return CgLayout.width(declared) == 0 && !CgLayout.isInt(declared);
            }
        }
    }

    public CgParam {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    public static CgParam<Float> ofFloat(String name) {
        return new CgParam<>(name, Type.FLOAT);
    }

    public static CgParam<Integer> ofInt(String name) {
        return new CgParam<>(name, Type.INT);
    }

    public static CgParam<Boolean> ofBool(String name) {
        return new CgParam<>(name, Type.BOOL);
    }

    /** An enum, read as its value's index in the declaration. */
    public static CgParam<Integer> ofEnum(String name) {
        return new CgParam<>(name, Type.ENUM);
    }

    public static CgParam<float[]> ofVec3(String name) {
        return new CgParam<>(name, Type.VEC3);
    }

    public static CgParam<float[]> ofVec4(String name) {
        return new CgParam<>(name, Type.VEC4);
    }

    public static CgParam<float[]> ofColour(String name) {
        return new CgParam<>(name, Type.COLOUR);
    }

    /** Anything held as an object: a curve, a mesh, a field. */
    public static <T> CgParam<T> ofObject(String name) {
        return new CgParam<>(name, Type.OBJECT);
    }

    @Override
    public String toString() {
        return "CgParam<" + type.name().toLowerCase(Locale.ROOT) + "> " + name;
    }
}
