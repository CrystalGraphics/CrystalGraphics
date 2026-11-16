package com.crystalgraphics.world;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.object.CgValueType;

/**
 * A field of an entity's own networked data, as something read each frame: a mod's mana, a form, a gear. The data is
 * a per-holder {@link CgNetType} the mod declares, set by its server and seen by the clients that see the entity
 * (with its owner rules and prediction); this names one of its number or bool fields so a graph can read it with no
 * code. Its file name is {@code <type>#<field>}.
 *
 * <pre>{@code
 * public static final CgNetField<Float> CURRENT = CgNetField.ofFloat("current");
 * public static final CgNetType MANA = CgNetType.define("mymod:mana").fields(CURRENT).perHolder().build();
 *
 * MANA.of(entity).setFloat(CURRENT, 80f);                       // the server, whenever it changes
 *
 * builder.entity(KI, CgEntityNetField.of(MANA, CURRENT));       // a graph's ki follows it on every side
 * }</pre>
 *
 * <pre>{@code
 * # anim_graphs/mymod/hero.yaml
 * entity:
 *   ki: mymod:mana#current
 * }</pre>
 *
 * <ul>
 *   <li>The type is per holder ({@code perHolder()}) and lists the field, which is a number or a bool; anything else
 *       is refused, naming it.</li>
 *   <li>Where the entity has no object of the type yet, or this client does not see it, it reads the field's
 *       fallback.</li>
 *   <li>A client reads what the server last sent: a {@code serverOnly()} field reads its fallback there.</li>
 * </ul>
 */
public final class CgEntityNetField implements CgEntityBinding {

    private final CgNetType netType;
    private final CgNetField<?> field;
    private final CgValueType type;
    private final float fallback;

    private CgEntityNetField(CgNetType netType, CgNetField<?> field, CgValueType type) {
        this.netType = netType;
        this.field = field;
        this.type = type;
        this.fallback = field.fallbackNumber();
    }

    /**
     * {@code field} of {@code type}, read on the entity's object of it.
     *
     * @throws IllegalArgumentException for a type not per holder, a field it does not list, or one no number or bool
     */
    public static CgEntityNetField of(CgNetType type, CgNetField<?> field) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(field, "field");
        if (!type.isPerHolder()) throw new IllegalArgumentException(type.name() + " is not per holder: an entity has none of its own");
        if (!type.lists(field)) throw new IllegalArgumentException(type.name() + " does not list " + field.name());
        CgValueType value = switch (field.kind()) {
            case FLOAT, DOUBLE -> CgValueType.FLOAT;
            case INT, LONG -> CgValueType.INT;
            case BOOL -> CgValueType.BOOL;
            case CODEC -> throw new IllegalArgumentException(type.name() + "#" + field.name() + " is no number or bool");
        };
        return new CgEntityNetField(type, field, value);
    }

    @Override
    public String name() {
        return netType.name() + "#" + field.name();
    }

    @Override
    public CgValueType type() {
        return type;
    }

    public CgNetType netType() {
        return netType;
    }

    public CgNetField<?> field() {
        return field;
    }

    /**
     * The entity's object of the type on this side: the server's for its {@code entity}, a client's by the entity's
     * network id. Null while there is none.
     */
    @Nullable
    public CgNetObject find(CgEntityFields.Source source) {
        if (source.onServer()) {
            Object entity = source.entity();
            return entity == null ? null : netType.find(entity);
        }
        return source.id() < 0 ? null : netType.findOnClient(source.id());
    }

    /** Its value on {@code object}, as a float; the field's fallback for none. Allocates nothing. */
    public float read(@Nullable CgNetObject object) {
        return object == null || object.isRemoved() ? fallback : object.getNumber(field);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgEntityNetField other && other.netType == netType && other.field == field;
    }

    @Override
    public int hashCode() {
        return 31 * netType.hashCode() + field.hashCode();
    }

    @Override
    public String toString() {
        return name() + ":" + type;
    }
}
