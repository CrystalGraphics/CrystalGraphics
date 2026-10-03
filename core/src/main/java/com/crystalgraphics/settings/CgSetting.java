package com.crystalgraphics.settings;

import java.util.Locale;
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
 * <ul>
 *   <li>A value is read from the file the first time any setting of that file is read, and again whenever the file
 *       changes on disk.</li>
 *   <li>Setting a value saves the file on the next frame; it is never written mid-frame.</li>
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

    /** Puts it back to its default. */
    public abstract void reset();

    /** The value as the file writes it. */
    abstract String write();

    /** Reads {@code text} from the file; false leaves the value as it was. */
    abstract boolean read(String text);

    /** The file comment's second line: its range or choices and its default. */
    abstract String hint();

    /** A number in a range, shown as a slider. */
    public static final class Number extends CgSetting {

        private final float fallback, min, max, step;
        private volatile float value;

        Number(CgSettings file, String section, String key, String label, String description,
               float fallback, float min, float max, float step) {
            super(file, section, key, label, description);
            if (!(min <= fallback && fallback <= max)) throw new IllegalArgumentException(id() + ": default outside its range");
            this.fallback = fallback;
            this.min = min;
            this.max = max;
            this.step = step;
            this.value = fallback;
        }

        public float get() {
            file.ensureLoaded();
            return value;
        }

        /** Clamped to the range; NaN is ignored. */
        public void set(float value) {
            if (Float.isNaN(value)) return;
            file.ensureLoaded();
            float clamped = Math.max(min, Math.min(max, value));
            if (clamped == this.value) return;
            this.value = clamped;
            file.changed();
        }

        @Override
        public void reset() {
            set(fallback);
        }

        public float defaultValue() {
            return fallback;
        }

        public float min() {
            return min;
        }

        public float max() {
            return max;
        }

        /** The slider's step; 0 for none. */
        public float step() {
            return step;
        }

        @Override
        String write() {
            return Float.toString(value);
        }

        @Override
        boolean read(String text) {
            try {
                float v = Float.parseFloat(text);
                if (Float.isNaN(v)) return false;
                value = Math.max(min, Math.min(max, v));
                return true;
            } catch (NumberFormatException bad) {
                return false;
            }
        }

        @Override
        String hint() {
            return min + " to " + max + ", default " + fallback;
        }
    }

    /** On or off. */
    public static final class Toggle extends CgSetting {

        private final boolean fallback;
        private volatile boolean value;

        Toggle(CgSettings file, String section, String key, String label, String description, boolean fallback) {
            super(file, section, key, label, description);
            this.fallback = fallback;
            this.value = fallback;
        }

        public boolean get() {
            file.ensureLoaded();
            return value;
        }

        public void set(boolean value) {
            file.ensureLoaded();
            if (value == this.value) return;
            this.value = value;
            file.changed();
        }

        @Override
        public void reset() {
            set(fallback);
        }

        public boolean defaultValue() {
            return fallback;
        }

        @Override
        String write() {
            return Boolean.toString(value);
        }

        @Override
        boolean read(String text) {
            if (text.equals("true")) value = true;
            else if (text.equals("false")) value = false;
            else return false;
            return true;
        }

        @Override
        String hint() {
            return "true or false, default " + fallback;
        }
    }

    /** One of an enum's constants, written as its name in lower case. */
    public static final class Choice<E extends Enum<E>> extends CgSetting {

        private final E fallback;
        private final E[] options;
        private volatile E value;

        Choice(CgSettings file, String section, String key, String label, String description, E fallback) {
            super(file, section, key, label, description);
            this.fallback = Objects.requireNonNull(fallback, "default");
            this.options = fallback.getDeclaringClass().getEnumConstants();
            this.value = fallback;
        }

        public E get() {
            file.ensureLoaded();
            return value;
        }

        public void set(E value) {
            Objects.requireNonNull(value, "value");
            file.ensureLoaded();
            if (value == this.value) return;
            this.value = value;
            file.changed();
        }

        @Override
        public void reset() {
            set(fallback);
        }

        public E defaultValue() {
            return fallback;
        }

        /** Every option, in declaration order. */
        public E[] options() {
            return options.clone();
        }

        @Override
        String write() {
            return "\"" + nameOf(value) + "\"";
        }

        @Override
        boolean read(String text) {
            for (E option : options) {
                if (nameOf(option).equals(text)) {
                    value = option;
                    return true;
                }
            }
            return false;
        }

        @Override
        String hint() {
            StringBuilder s = new StringBuilder();
            for (E option : options) s.append(s.length() == 0 ? "" : ", ").append(nameOf(option));
            return s.append("; default ").append(nameOf(fallback)).toString();
        }

        private static String nameOf(Enum<?> option) {
            return option.name().toLowerCase(Locale.ROOT);
        }
    }
}
