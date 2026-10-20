package com.crystalgraphics.object;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The type of a property's value: a number, a vector, a reference, a struct, a list of elements.
 *
 * <p>Mirrors what Unreal's {@code FProperty} subclasses are to a {@code UPROPERTY} ({@code FFloatProperty},
 * {@code FStructProperty}, {@code FArrayProperty}, {@code FSoftObjectProperty}), and Niagara's
 * {@code FNiagaraTypeDefinition} for inputs. The scalar and vector types are the format's values
 * ({@code docs/vfx/assets.md} § Values); the rest are the shapes the 20 kinds hold.</p>
 *
 * <pre>{@code
 * CgValueType.FLOAT
 * CgValueType.enumOf("loop", "infinite", "multiple", "once")
 * CgValueType.ref("material", Strength.HARD)                 // an emitter's material: loaded with it
 * CgValueType.ref("sequence", Strength.SOFT)                 // a cue's sequence: loaded on first use
 * CgValueType.idList(EMITTER_ELEMENT)                        // elements merged by id under inheritance
 * CgValueType.listOf(CgValueType.FLOAT)                      // a plain list: replaced whole
 * CgValueType.dispatch(SHAKE_PATTERNS)                       // a kind chosen by a key
 * }</pre>
 *
 * <ul>
 *   <li>{@link #idList} and {@link #listOf} differ under inheritance: an id list merges element by element, a plain
 *       list is replaced whole.</li>
 *   <li>A reference's strength is the field's declaration, never the file's.</li>
 *   <li>{@link #TREE} is a subtree kept as written and never typed below its root: {@code editor} and
 *       {@code unsupported} blocks.</li>
 * </ul>
 */
public final class CgValueType {

    /** What shape of value it is. */
    public enum Kind {
        FLOAT, INT, BOOL, STRING, ENUM, VEC2, VEC3, VEC4, COLOUR, ROTATION, POSITION, CURVE,
        MESH, TEXTURE, FIELD, ARRAY, REF, ELEMENT_REF, BINARY_REF, STRUCT, LIST, ID_LIST, MAP, DISPATCH, TREE,
        VALUE, ONE_OF, SELF
    }

    /** Whether a reference loads with its referrer or on first use. */
    public enum Strength { HARD, SOFT }

    /** A number. */
    public static final CgValueType FLOAT = new CgValueType(Kind.FLOAT);
    /** A whole number. */
    public static final CgValueType INT = new CgValueType(Kind.INT);
    /** {@code true} or {@code false}. */
    public static final CgValueType BOOL = new CgValueType(Kind.BOOL);
    /** Text: a name, an id, an event. */
    public static final CgValueType STRING = new CgValueType(Kind.STRING);
    /** Two numbers. */
    public static final CgValueType VEC2 = new CgValueType(Kind.VEC2);
    /** Three numbers. */
    public static final CgValueType VEC3 = new CgValueType(Kind.VEC3);
    /** Four numbers. */
    public static final CgValueType VEC4 = new CgValueType(Kind.VEC4);
    /** Floats in the declared space, or {@code { argb, intensity }}. */
    public static final CgValueType COLOUR = new CgValueType(Kind.COLOUR);
    /** A rotation in the asset's convention: its {@code units}' {@code rotation} and {@code angles}. */
    public static final CgValueType ROTATION = new CgValueType(Kind.ROTATION);
    /** A point the player binds or the file states. */
    public static final CgValueType POSITION = new CgValueType(Kind.POSITION);
    /** A curve inline or by id. */
    public static final CgValueType CURVE = new CgValueType(Kind.CURVE);
    /** A mesh data input: {@code { mesh: <id> }}. */
    public static final CgValueType MESH = new CgValueType(Kind.MESH);
    /** A texture data input: {@code { texture: <id> }}. */
    public static final CgValueType TEXTURE = new CgValueType(Kind.TEXTURE);
    /** A field data input: {@code { field: <id> }}. */
    public static final CgValueType FIELD = new CgValueType(Kind.FIELD);
    /** An array input's values, read by an array form or as a constant list. */
    public static final CgValueType ARRAY = new CgValueType(Kind.ARRAY);
    /** A subtree kept as written: {@code editor}, {@code unsupported}. */
    public static final CgValueType TREE = new CgValueType(Kind.TREE);
    /** A struct whose fields the format leaves open (a module input of {@code type: struct} with no shape). */
    public static final CgValueType OPEN_STRUCT = new CgValueType(Kind.STRUCT);
    /**
     * A value in any form whose type another declaration gives: a module entry's {@code inputs} (the module's
     * inputs), a renderer's {@code values} (its material's parameters), a sequence channel's key.
     */
    public static final CgValueType VALUE = new CgValueType(Kind.VALUE);
    /**
     * A value of the type that declares the property: a condition's {@code not}, a list of conditions in its
     * {@code all}. The type does not exist yet while it is declared, so it names itself this way.
     */
    public static final CgValueType SELF = new CgValueType(Kind.SELF);

    private final Kind kind;
    private final List<String> enumValues;
    private final String refKind;
    private final Strength strength;
    private final CgType type;
    private final CgValueType element;
    private final CgValueType key;
    private final List<CgValueType> alternatives;

    private CgValueType(Kind kind) {
        this(kind, Collections.<String>emptyList(), null, null, null, null, null);
    }

    private CgValueType(Kind kind, List<String> enumValues, String refKind, Strength strength, CgType type,
                        CgValueType element, CgValueType key) {
        this(kind, enumValues, refKind, strength, type, element, key, Collections.<CgValueType>emptyList());
    }

    private CgValueType(Kind kind, List<String> enumValues, String refKind, Strength strength, CgType type,
                        CgValueType element, CgValueType key, List<CgValueType> alternatives) {
        this.alternatives = alternatives;
        this.kind = kind;
        this.enumValues = enumValues;
        this.refKind = refKind;
        this.strength = strength;
        this.type = type;
        this.element = element;
        this.key = key;
    }

    /** One of {@code values}, written as its name. */
    public static CgValueType enumOf(String... values) {
        if (values.length == 0) throw new IllegalArgumentException("an enum needs values");
        return new CgValueType(Kind.ENUM, Collections.unmodifiableList(Arrays.asList(values.clone())),
                null, null, null, null, null);
    }

    /** A reference to an asset of kind {@code assetKind} ({@code system}, {@code material}), by id. */
    public static CgValueType ref(String assetKind, Strength strength) {
        return new CgValueType(Kind.REF, Collections.<String>emptyList(), Objects.requireNonNull(assetKind),
                Objects.requireNonNull(strength), null, null, null);
    }

    /** An element of the same asset, by id: {@code { spawnable: s4 }}. {@code elementKind} is the map's key. */
    public static CgValueType elementRef(String elementKind) {
        return new CgValueType(Kind.ELEMENT_REF, Collections.<String>emptyList(), Objects.requireNonNull(elementKind),
                null, null, null, null);
    }

    /** A binary file by id with its extension: a glTF, a PNG, a sound. {@code folder} is where it lives. */
    public static CgValueType binaryRef(String folder) {
        return new CgValueType(Kind.BINARY_REF, Collections.<String>emptyList(), Objects.requireNonNull(folder),
                Strength.HARD, null, null, null);
    }

    /** A value of a declared struct. */
    public static CgValueType struct(CgType type) {
        return new CgValueType(Kind.STRUCT, Collections.<String>emptyList(), null, null, Objects.requireNonNull(type),
                null, null);
    }

    /** A plain list: replaced whole under inheritance. */
    public static CgValueType listOf(CgValueType element) {
        return new CgValueType(Kind.LIST, Collections.<String>emptyList(), null, null, null,
                Objects.requireNonNull(element), null);
    }

    /** A list of elements, each with an {@code id}, merged by id under inheritance. */
    public static CgValueType idList(CgType element) {
        if (element.kind() != CgTypeKind.ELEMENT && element.kind() != CgTypeKind.DISPATCH) {
            throw new IllegalArgumentException(element.id() + " is not an element type: an id list holds elements");
        }
        return new CgValueType(Kind.ID_LIST, Collections.<String>emptyList(), null, null, element, null, null);
    }

    /** A map from text keys, merged by key under inheritance. */
    public static CgValueType mapOf(CgValueType value) {
        return new CgValueType(Kind.MAP, Collections.<String>emptyList(), null, null, null,
                Objects.requireNonNull(value), STRING);
    }

    /**
     * One of several shapes, told apart by how it is written: a reference or an inline asset
     * ({@code emitter: <id>} or {@code emitter: { ... }}), {@code dynamic} or {@code { fixed: ... }}.
     * Alternatives are tried in order; a value none accepts fails, naming them all.
     */
    public static CgValueType oneOf(CgValueType first, CgValueType... rest) {
        CgValueType[] all = new CgValueType[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return new CgValueType(Kind.ONE_OF, Collections.<String>emptyList(), null, null, null, null, null,
                Collections.unmodifiableList(Arrays.asList(all)));
    }

    /** A member of a dispatch family, chosen by the family's key ({@code kind:}, {@code pattern:}). */
    public static CgValueType dispatch(CgType family) {
        if (family.kind() != CgTypeKind.DISPATCH) throw new IllegalArgumentException(family.id() + " is not a dispatch family");
        return new CgValueType(Kind.DISPATCH, Collections.<String>emptyList(), null, null, family, null, null);
    }

    /** What shape of value it is. */
    public Kind kind() {
        return kind;
    }

    /** An enum's values, in declared order; empty for any other kind. */
    public List<String> enumValues() {
        return enumValues;
    }

    /** A reference's asset kind, an element reference's element kind, or a binary's folder; null otherwise. */
    public String refKind() {
        return refKind;
    }

    /** A reference's strength; null for anything but a reference. */
    public Strength strength() {
        return strength;
    }

    /** A struct's, an id list's element or a dispatch family's type; null otherwise and for {@link #OPEN_STRUCT}. */
    public CgType type() {
        return type;
    }

    /** A list's or a map's element type; null otherwise. */
    public CgValueType element() {
        return element;
    }

    /** A {@link #oneOf}'s alternatives, in the order they are tried; empty otherwise. */
    public List<CgValueType> alternatives() {
        return alternatives;
    }

    /**
     * Whether a property of this type takes every value form unless its declaration narrows it: a number, a vector,
     * a curve, or a value typed elsewhere.
     */
    public boolean takesEveryForm() {
        switch (kind) {
            case FLOAT: case INT: case VEC2: case VEC3: case VEC4: case COLOUR: case ROTATION: case POSITION: case CURVE: case VALUE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgValueType)) return false;
        CgValueType t = (CgValueType) o;
        return kind == t.kind && enumValues.equals(t.enumValues) && Objects.equals(refKind, t.refKind)
                && strength == t.strength && type == t.type && Objects.equals(element, t.element)
                && Objects.equals(key, t.key) && alternatives.equals(t.alternatives);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, enumValues, refKind, strength, type == null ? 0 : System.identityHashCode(type), element,
                alternatives);
    }

    @Override
    public String toString() {
        switch (kind) {
            case ENUM: return "enum" + enumValues;
            case REF: return "ref<" + refKind + ", " + strength.name().toLowerCase() + ">";
            case ELEMENT_REF: return "element<" + refKind + ">";
            case BINARY_REF: return "binary<" + refKind + ">";
            case STRUCT: return type == null ? "struct" : "struct<" + type.id() + ">";
            case LIST: return "list<" + element + ">";
            case ID_LIST: return "id-list<" + type.id() + ">";
            case MAP: return "map<" + element + ">";
            case DISPATCH: return "dispatch<" + type.id() + ">";
            case ONE_OF: return "one-of" + alternatives;
            default: return kind.name().toLowerCase();
        }
    }
}
