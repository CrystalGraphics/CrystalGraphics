package com.crystalgraphics.variable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One typed variable of the engine or a mod: an id, a default, a range or choices, a description, and a value that
 * code, a settings file, a {@code -D} flag, the console or a remote tool may set. The highest {@link Source} that set
 * it wins; with none, its default. Every variable is listed in {@link CgVars}, which is what a console, a remote tool
 * and {@code docs/DEBUG_FLAGS.md} read.
 *
 * <pre>{@code
 * // declared once, a static final beside what reads it
 * static final CgVar.Bool MERGE = CgVar.bool("crystalgraphics.vfx.mergeParticles", true, CgVar.DEV,
 *         "GPU particles a draw a pool and layer; false draws a slot a draw, sorted per effect.");
 * static final CgVar.Int STEP = CgVar.integer("crystalgraphics.vfx.particleStep", 2, 1, 8, CgVar.DEV,
 *         "Ticks a particle step spans.");
 *
 * if (MERGE.get()) ...                              // a volatile read: fine in a hot path
 * STEP.onChange(step -> rebuild(step));             // runs on the thread that changed it
 * STEP.set(1);                                      // CODE: -D, the console and remote still win
 * STEP.set(CgVar.Source.CONSOLE, 1);                // a dev tool's key: outranks -D
 * }</pre>
 *
 * <p>Read once at startup and never again? Declare it {@link #RESTART}: later changes are kept for the next launch
 * and logged, never half-applied.</p>
 *
 * <pre>{@code
 * static final CgVar.Int THREADS = CgVar.integer("crystalgraphics.job.threads", 0, 0, 256, CgVar.DEV | CgVar.RESTART,
 *         "Threads jobs' work runs on; 0 for half the cores past two.");
 * }</pre>
 *
 * <ul>
 *   <li>{@code -D<id>=<value>} is read when the variable is declared; a value it cannot take is logged and ignored.
 *       A bare {@code -D<id>} turns a {@link Bool} on.</li>
 *   <li>A number set out of its range is clamped.</li>
 *   <li>Ids are unique: declaring one twice throws, so a variable belongs to one class.</li>
 *   <li>{@link #READ_ONLY} refuses the console and remote; code, the file and {@code -D} still set it.</li>
 * </ul>
 */
public abstract class CgVar<T> {

    /** Who set a value, lowest first: a higher one wins while it holds a value. */
    public enum Source {
        /** The program's own choice: a mod's default, a settings screen previewing a value. */
        CODE,
        /** A settings file ({@code CgSettings}). */
        FILE,
        /** A {@code -D} flag on the command line. */
        PROPERTY,
        /** A person, live: the in-game console, a dev tool's switch (the harness's keys). */
        CONSOLE,
        /** A remote tool. */
        REMOTE
    }

    /** For developers: a tool listing what a player may change leaves it out. */
    public static final int DEV = 1;
    /** Set by code, the file and {@code -D} only; the console and remote are refused. */
    public static final int READ_ONLY = 1 << 1;
    /** Read once: a change after its first read waits for the next launch. */
    public static final int RESTART = 1 << 2;
    /** Kept in a settings file: a player's setting. */
    public static final int SAVED = 1 << 3;

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final Source[] SOURCES = Source.values();

    private final String id, description;
    private final int flags;
    final T fallback;
    private final Object[] bySource = new Object[SOURCES.length];
    private final List<Consumer<? super T>> listeners = new CopyOnWriteArrayList<>();
    /** The value its readers see; for a restart variable, the one it had at its first read. */
    volatile T value;
    /** A restart variable has been read: later changes wait for the next launch. */
    volatile boolean latched;
    private boolean warnedRestart;

    CgVar(String id, T fallback, int flags, String description) {
        if (id == null || !id.matches("[a-z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) {
            throw new IllegalArgumentException("a variable id is dotted words, as crystalgraphics.vfx.skip: '" + id + "'");
        }
        this.id = id;
        this.fallback = fallback;
        this.flags = flags;
        this.description = Objects.requireNonNull(description, "description");
        this.value = fallback;
    }

    // ── declaring ──────────────────────────────────────────────────────────────

    public static Bool bool(String id, boolean fallback, String description) {
        return bool(id, fallback, 0, description);
    }

    public static Bool bool(String id, boolean fallback, int flags, String description) {
        return CgVars.register(new Bool(id, fallback, flags, description));
    }

    public static Int integer(String id, int fallback, int min, int max, String description) {
        return integer(id, fallback, min, max, 0, description);
    }

    public static Int integer(String id, int fallback, int min, int max, int flags, String description) {
        return CgVars.register(new Int(id, fallback, min, max, flags, description));
    }

    public static Num number(String id, float fallback, float min, float max, String description) {
        return number(id, fallback, min, max, 0, description);
    }

    public static Num number(String id, float fallback, float min, float max, int flags, String description) {
        return CgVars.register(new Num(id, fallback, min, max, flags, description));
    }

    /** Free text; empty is unset. */
    public static Text text(String id, String fallback, String description) {
        return text(id, fallback, 0, description);
    }

    public static Text text(String id, String fallback, int flags, String description) {
        return CgVars.register(new Text(id, fallback, flags, description));
    }

    /** One of an enum's constants, written as its name in lower case. */
    public static <E extends Enum<E>> Choice<E> choice(String id, E fallback, String description) {
        return choice(id, fallback, 0, description);
    }

    public static <E extends Enum<E>> Choice<E> choice(String id, E fallback, int flags, String description) {
        return CgVars.register(new Choice<>(id, fallback, flags, description));
    }

    // ── what it is ─────────────────────────────────────────────────────────────

    public final String id() {
        return id;
    }

    public final String description() {
        return description;
    }

    public final boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** {@code bool}, {@code int}, {@code float}, {@code text} or {@code choice}. */
    public abstract String type();

    /** Its range or choices, as text; empty for none. */
    public abstract String range();

    /** Its value now, boxed: a tool's read. A reader in a loop uses the type's own {@code get}. */
    public final T value() {
        if (has(RESTART)) latched = true;
        return value;
    }

    public final T defaultValue() {
        return fallback;
    }

    public final String text() {
        return format(value());
    }

    public final String defaultText() {
        return format(fallback);
    }

    // ── setting ────────────────────────────────────────────────────────────────

    /**
     * Sets the value {@code source} holds; it shows while no higher source holds one. False, changing nothing, when
     * the variable is read-only and the source the console or remote.
     */
    public final boolean set(Source source, T value) {
        Objects.requireNonNull(value, "value");
        if (refuses(source)) return false;
        T clamped = clamp(value);
        synchronized (this) {
            bySource[source.ordinal()] = clamped;
        }
        resolve();
        return true;
    }

    /** As {@link #set}, from text: false when it is not a value it takes, or refused. */
    public final boolean setText(Source source, String text) {
        T parsed = text == null ? null : parse(text.trim());
        return parsed != null && set(source, parsed);
    }

    /** Drops what {@code source} holds, so the next source down, or the default, shows. */
    public final boolean clear(Source source) {
        if (refuses(source)) return false;
        synchronized (this) {
            bySource[source.ordinal()] = null;
        }
        resolve();
        return true;
    }

    /** What {@code source} holds; null for nothing. */
    @SuppressWarnings("unchecked")
    public final synchronized T valueAt(Source source) {
        return (T) bySource[source.ordinal()];
    }

    /** The source whose value shows; null for the default. */
    public final synchronized Source source() {
        for (int i = SOURCES.length - 1; i >= 0; i--) {
            if (bySource[i] != null) return SOURCES[i];
        }
        return null;
    }

    /** Hears each change of the value it shows, on the thread that changed it. A restart variable never calls it. */
    public final void onChange(Consumer<? super T> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public final void removeListener(Consumer<? super T> listener) {
        listeners.remove(listener);
    }

    private boolean refuses(Source source) {
        return has(READ_ONLY) && (source == Source.CONSOLE || source == Source.REMOTE);
    }

    @SuppressWarnings("unchecked")
    private void resolve() {
        T next = fallback;
        synchronized (this) {
            for (int i = SOURCES.length - 1; i >= 0; i--) {
                if (bySource[i] != null) {
                    next = (T) bySource[i];
                    break;
                }
            }
            if (next.equals(value)) return;
            if (has(RESTART) && latched) {
                if (!warnedRestart) LOGGER.info("[vars] {} = {} takes effect at the next launch", id, format(next));
                warnedRestart = true;
                return;
            }
            value = next;
        }
        for (Consumer<? super T> listener : listeners) listener.accept(next);
    }

    /** The {@code -D} flag, at declaration. */
    final void readProperty() {
        String raw;
        try {
            raw = System.getProperty(id);
        } catch (SecurityException denied) {
            return;
        }
        if (raw == null) return;
        if (!setText(Source.PROPERTY, raw)) {
            LOGGER.warn("[vars] -D{}={} is not a value it takes ({} {}); keeping {}", id, raw, type(), range(), text());
        }
    }

    abstract T parse(String text);

    abstract T clamp(T value);

    /** {@code value} as {@link #setText} reads it back. */
    public String format(T value) {
        return String.valueOf(value);
    }

    @Override
    public final String toString() {
        return id + " = " + text();
    }

    // ── types ──────────────────────────────────────────────────────────────────

    /** On or off: {@code true}, {@code false}, or a bare {@code -D} flag for on. */
    public static final class Bool extends CgVar<Boolean> {

        private volatile boolean flag;

        Bool(String id, boolean fallback, int flags, String description) {
            super(id, fallback, flags, description);
            flag = fallback;
            onChange(v -> flag = v);
        }

        public boolean get() {
            if (has(RESTART) && !latched) latched = true;
            return flag;
        }

        public void set(boolean value) {
            set(Source.CODE, value);
        }

        @Override
        public String type() {
            return "bool";
        }

        @Override
        public String range() {
            return "";
        }

        @Override
        Boolean parse(String text) {
            if (text.isEmpty() || text.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (text.equalsIgnoreCase("false")) return Boolean.FALSE;
            return null;
        }

        @Override
        Boolean clamp(Boolean value) {
            return value;
        }
    }

    /** A whole number in a range. */
    public static final class Int extends CgVar<Integer> {

        private final int min, max;
        private volatile int number;

        Int(String id, int fallback, int min, int max, int flags, String description) {
            super(id, fallback, flags, description);
            if (!(min <= fallback && fallback <= max)) throw new IllegalArgumentException(id + ": default outside its range");
            this.min = min;
            this.max = max;
            number = fallback;
            onChange(v -> number = v);
        }

        public int get() {
            if (has(RESTART) && !latched) latched = true;
            return number;
        }

        public void set(int value) {
            set(Source.CODE, value);
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        @Override
        public String type() {
            return "int";
        }

        @Override
        public String range() {
            return min + " to " + max;
        }

        @Override
        Integer parse(String text) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException bad) {
                return null;
            }
        }

        @Override
        Integer clamp(Integer value) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /** A number in a range. */
    public static final class Num extends CgVar<Float> {

        private final float min, max;
        private volatile float number;

        Num(String id, float fallback, float min, float max, int flags, String description) {
            super(id, fallback, flags, description);
            if (!(min <= fallback && fallback <= max)) throw new IllegalArgumentException(id + ": default outside its range");
            this.min = min;
            this.max = max;
            number = fallback;
            onChange(v -> number = v);
        }

        public float get() {
            if (has(RESTART) && !latched) latched = true;
            return number;
        }

        public void set(float value) {
            set(Source.CODE, value);
        }

        public float min() {
            return min;
        }

        public float max() {
            return max;
        }

        @Override
        public String type() {
            return "float";
        }

        @Override
        public String range() {
            return min + " to " + max;
        }

        @Override
        Float parse(String text) {
            try {
                float v = Float.parseFloat(text);
                return Float.isNaN(v) ? null : v;
            } catch (NumberFormatException bad) {
                return null;
            }
        }

        @Override
        Float clamp(Float value) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /** Free text; empty is unset. */
    public static final class Text extends CgVar<String> {

        Text(String id, String fallback, int flags, String description) {
            super(id, Objects.requireNonNull(fallback, "default: empty, not null"), flags, description);
        }

        public String get() {
            return value();
        }

        public boolean isSet() {
            return !value().isEmpty();
        }

        public void set(String value) {
            set(Source.CODE, value);
        }

        @Override
        public String type() {
            return "text";
        }

        @Override
        public String range() {
            return "";
        }

        @Override
        String parse(String text) {
            return text;
        }

        @Override
        String clamp(String value) {
            return value;
        }
    }

    /** One of an enum's constants, written as its name in lower case, read in any case. */
    public static final class Choice<E extends Enum<E>> extends CgVar<E> {

        private final E[] options;

        Choice(String id, E fallback, int flags, String description) {
            super(id, Objects.requireNonNull(fallback, "default"), flags, description);
            options = fallback.getDeclaringClass().getEnumConstants();
        }

        public E get() {
            return value();
        }

        public void set(E value) {
            set(Source.CODE, value);
        }

        /** Every option, in declaration order. */
        public E[] options() {
            return options.clone();
        }

        @Override
        public String type() {
            return "choice";
        }

        @Override
        public String range() {
            StringBuilder s = new StringBuilder();
            for (E option : options) s.append(s.length() == 0 ? "" : ", ").append(nameOf(option));
            return s.toString();
        }

        @Override
        E parse(String text) {
            for (E option : options) {
                if (option.name().equalsIgnoreCase(text)) return option;
            }
            return null;
        }

        @Override
        E clamp(E value) {
            return value;
        }

        @Override
        public String format(E value) {
            return nameOf(value);
        }

        static String nameOf(Enum<?> option) {
            return option.name().toLowerCase(Locale.ROOT);
        }
    }
}
