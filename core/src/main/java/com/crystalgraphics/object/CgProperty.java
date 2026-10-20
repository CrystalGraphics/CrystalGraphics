package com.crystalgraphics.object;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgValueForm;

/**
 * One declared property of a {@link CgType}: its name, value type, quantity, accepted forms, default, flags and
 * metadata. Mirrors Unreal's {@code FProperty} with its {@code EPropertyFlags} and metadata: one declaration that
 * the codec, inheritance, validation, units, undo, the editor's details panel and replication all read.
 *
 * <p>Declared through a type's builder, in the order a file writes it:</p>
 *
 * <pre>{@code
 * CgType SHAKE = CgTypes.kind("crystalgraphics:shake").folder("cameras/shakes").version(1)
 *         .property("pattern", CgValueType.dispatch(SHAKE_PATTERNS)).required().flags(CgPropertyFlag.STRUCTURAL)
 *         .property("duration", CgValueType.FLOAT).quantity(CgQuantity.TIME).defaultValue(1.0).range(0, null)
 *         .register();
 *
 * CgProperty duration = SHAKE.property("duration");
 * duration.quantity();                                   // TIME: converted by the asset's time units
 * duration.accepts(CgValueForm.CURVE);                   // true: a number takes every form unless narrowed
 * duration.has(CgPropertyFlag.STRUCTURAL);               // false: editing it patches what plays
 * }</pre>
 *
 * <ul>
 *   <li>{@link #defaultValue()} is the value as a file would write it (a number, a list, a string), or null where
 *       the spec gives none.</li>
 *   <li>Immutable once its type is registered.</li>
 * </ul>
 */
public final class CgProperty {

    private final CgType owner;
    private final int index;
    private final String name;
    private final CgValueType type;
    private final CgQuantity quantity;
    private final Set<CgValueForm> forms;
    private final Object defaultValue;
    private final boolean required;
    private final Set<CgPropertyFlag> flags;
    private final CgPropertyMeta meta;

    CgProperty(CgType owner, int index, String name, CgValueType type, CgQuantity quantity, Set<CgValueForm> forms,
               Object defaultValue, boolean required, Set<CgPropertyFlag> flags, CgPropertyMeta meta) {
        this.owner = owner;
        this.index = index;
        this.name = name;
        this.type = type;
        this.quantity = quantity;
        this.forms = Collections.unmodifiableSet(forms.isEmpty() ? EnumSet.noneOf(CgValueForm.class) : EnumSet.copyOf(forms));
        this.defaultValue = defaultValue;
        this.required = required;
        this.flags = Collections.unmodifiableSet(flags.isEmpty() ? EnumSet.noneOf(CgPropertyFlag.class) : EnumSet.copyOf(flags));
        this.meta = meta;
    }

    /** The type that declares it. */
    public CgType owner() {
        return owner;
    }

    /** Its place in declaration order: the order a file writes keys in, and a layout's slot order. */
    public int index() {
        return index;
    }

    /** Its key in a file: {@code sprite_size}. */
    public String name() {
        return name;
    }

    /** What its value is. */
    public CgValueType type() {
        return type;
    }

    /** What its number measures, which decides how it converts to the host's units. */
    public CgQuantity quantity() {
        return quantity;
    }

    /** The value forms it accepts: a constant, a range, a curve, a binding… */
    public Set<CgValueForm> forms() {
        return forms;
    }

    /** Whether a file may write it in {@code form}. */
    public boolean accepts(CgValueForm form) {
        return forms.contains(form);
    }

    /** Its value with nothing written, as a file would write it; null where the spec gives none. */
    public Object defaultValue() {
        return defaultValue;
    }

    /** Whether a file must hold it (once its parent's are merged in). */
    public boolean required() {
        return required;
    }

    /** What it is to each system that reads it. */
    public Set<CgPropertyFlag> flags() {
        return flags;
    }

    /** Whether it carries {@code flag}. */
    public boolean has(CgPropertyFlag flag) {
        return flags.contains(flag);
    }

    /** What it tells the editor: range, description, edit conditions, replication mode. */
    public CgPropertyMeta meta() {
        return meta;
    }

    @Override
    public String toString() {
        return owner.id() + "." + name + ": " + type;
    }
}
