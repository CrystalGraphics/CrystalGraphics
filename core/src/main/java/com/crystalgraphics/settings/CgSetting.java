package com.crystalgraphics.settings;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.variable.CgVar;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * One setting a player can change: a section and key in its {@link CgSettings} file, a label and description for any
 * screen that shows it, a default, and its current value. Made only through a file's {@code number}, {@code toggle} and
 * {@code choice}.
 *
 * <pre>{@code
 * static final CgSettings FILE = CgSettings.file("mymod");
 * static final CgSetting.Number DENSITY = FILE.number("vfx", "density", "Particle density",
 *         "Share of each effect's particles to spawn.", 1f, 0f, 1f, 0.05f);
 *
 * float share = DENSITY.get();      // render thread, every tick if need be: a field read
 * DENSITY.set(0.5f);                // clamped to the range, saved within a second
 * }</pre>
 *
 * <p>Each is a {@link CgVar} too, {@code <file>.<section>.<key>} ({@link #var()}): the file is its
 * {@link CgVar.Source#FILE} value, so {@code -Dmymod.vfx.density=0.2}, the console or a tool outrank it for the run
 * without being saved over the player's choice.</p>
 *
 * <ul>
 *   <li>A value is read from the file the first time any setting of that file is read, and again whenever the file
 *       changes on disk.</li>
 *   <li>Setting a value saves the file on the next frame; it is never written mid-frame.</li>
 *   <li>{@link #get} is the value in force, whoever set it; a screen editing the player's choice shows
 *       {@code saved()}.</li>
 * </ul>
 */
public abstract class CgSetting {

    final CgSettings file;
    private final String section, key, label, description;

    CgSetting(CgSettings file, String section, String key, String label, String description) {
        this.file = file;
        this.section = Objects.requireNonNull(section, "section");
        this.key = Objects.requireNonNull(key, "key");
        this.label = Objects.requireNonNull(label, "label");
        this.description = Objects.requireNonNull(description, "description");
    }

    /** The {@code [section]} it is written under. */
    public final String section() {
        return section;
    }

    /** Its key within the section. */
    public final String key() {
        return key;
    }

    /** {@code section.key}. */
    public final String id() {
        return section + "." + key;
    }

    /** A short name for a screen to show. */
    public final String label() {
        return label;
    }

    /** One or two sentences on what it does, for a screen and the file's comment. */
    public final String description() {
        return description;
    }

    /** The variable it is: {@code <file>.<section>.<key>}. */
    public abstract CgVar<?> var();

    /** Puts the player's choice back to its default. */
    public abstract void reset();

    /** The player's choice as the file holds it: a number, a boolean, a choice's name. */
    abstract <T> T encode(CgDynamicOps<T> ops);

    /** The player's choice as text, for a warning. */
    abstract String savedText();

    /** Reads {@code value} from the file; false leaves the value as it was. */
    final <T> boolean decode(CgDynamicOps<T> ops, T value) {
        String text = textOf(ops, value);
        return text != null && var().setText(CgVar.Source.FILE, text);
    }

    /** What the file holds as text, whatever its kind: a quoted {@code "0.5"} reads as well as {@code 0.5}. */
    @Nullable
    private static <T> String textOf(CgDynamicOps<T> ops, T value) {
        try {
            return ops.getStringValue(value);
        } catch (CgCodecException notText) {
            // a number or a boolean
        }
        try {
            return String.valueOf(ops.getBooleanValue(value));
        } catch (CgCodecException notBoolean) {
            // a number
        }
        try {
            return ops.getNumberValue(value).toString();
        } catch (CgCodecException neither) {
            return null;
        }
    }

    /** The file comment's second line: its range or choices and its default. */
    final String hint() {
        CgVar<?> v = var();
        if (v instanceof CgVar.Bool) return "true or false, default " + v.defaultText();
        return v.range() + (v instanceof CgVar.Choice ? "; default " : ", default ") + v.defaultText();
    }

    static String varId(CgSettings file, String section, String key) {
        return file.name() + "." + section + "." + key;
    }

    /** Sets the file's value to {@code value} and marks the file to save, if that changed it. */
    <T> void setSaved(CgVar<T> v, T value) {
        file.ensureLoaded();
        T before = v.valueAt(CgVar.Source.FILE);
        v.set(CgVar.Source.FILE, value);
        if (!v.valueAt(CgVar.Source.FILE).equals(before == null ? v.defaultValue() : before)) file.changed();
    }

    static <T> T saved(CgVar<T> v) {
        T at = v.valueAt(CgVar.Source.FILE);
        return at != null ? at : v.defaultValue();
    }

    /** A number in a range, shown as a slider. */
    public static final class Number extends CgSetting {

        private final CgVar.Num var;
        private final float step;

        Number(CgSettings file, String section, String key, String label, String description,
               float fallback, float min, float max, float step) {
            super(file, section, key, label, description);
            this.var = CgVar.number(varId(file, section, key), fallback, min, max, CgVar.SAVED, description);
            this.step = step;
        }

        public float get() {
            file.ensureLoaded();
            return var.get();
        }

        /** The player's choice, whatever outranks it for the run. */
        public float saved() {
            file.ensureLoaded();
            return saved(var);
        }

        /** Clamped to the range; NaN is ignored. */
        public void set(float value) {
            if (Float.isNaN(value)) return;
            setSaved(var, value);
        }

        @Override
        public CgVar.Num var() {
            return var;
        }

        @Override
        public void reset() {
            set(var.defaultValue());
        }

        public float defaultValue() {
            return var.defaultValue();
        }

        public float min() {
            return var.min();
        }

        public float max() {
            return var.max();
        }

        /** The slider's step; 0 for none. */
        public float step() {
            return step;
        }

        @Override
        <T> T encode(CgDynamicOps<T> ops) {
            return ops.createNumber(saved(var));
        }

        @Override
        String savedText() {
            return Float.toString(saved(var));
        }
    }

    /** On or off. */
    public static final class Toggle extends CgSetting {

        private final CgVar.Bool var;

        Toggle(CgSettings file, String section, String key, String label, String description, boolean fallback) {
            super(file, section, key, label, description);
            this.var = CgVar.bool(varId(file, section, key), fallback, CgVar.SAVED, description);
        }

        public boolean get() {
            file.ensureLoaded();
            return var.get();
        }

        /** The player's choice, whatever outranks it for the run. */
        public boolean saved() {
            file.ensureLoaded();
            return saved(var);
        }

        public void set(boolean value) {
            setSaved(var, value);
        }

        @Override
        public CgVar.Bool var() {
            return var;
        }

        @Override
        public void reset() {
            set(var.defaultValue());
        }

        public boolean defaultValue() {
            return var.defaultValue();
        }

        @Override
        <T> T encode(CgDynamicOps<T> ops) {
            return ops.createBoolean(saved(var));
        }

        @Override
        String savedText() {
            return Boolean.toString(saved(var));
        }
    }

    /** One of an enum's constants, written as its name in lower case. */
    public static final class Choice<E extends Enum<E>> extends CgSetting {

        private final CgVar.Choice<E> var;

        Choice(CgSettings file, String section, String key, String label, String description, E fallback) {
            super(file, section, key, label, description);
            this.var = CgVar.choice(varId(file, section, key), fallback, CgVar.SAVED, description);
        }

        public E get() {
            file.ensureLoaded();
            return var.get();
        }

        /** The player's choice, whatever outranks it for the run. */
        public E saved() {
            file.ensureLoaded();
            return saved(var);
        }

        public void set(E value) {
            setSaved(var, Objects.requireNonNull(value, "value"));
        }

        @Override
        public CgVar.Choice<E> var() {
            return var;
        }

        @Override
        public void reset() {
            set(var.defaultValue());
        }

        public E defaultValue() {
            return var.defaultValue();
        }

        /** Every option, in declaration order. */
        public E[] options() {
            return var.options();
        }

        @Override
        <T> T encode(CgDynamicOps<T> ops) {
            return ops.createString(var.format(saved(var)));
        }

        @Override
        String savedText() {
            return var.format(saved(var));
        }
    }
}
