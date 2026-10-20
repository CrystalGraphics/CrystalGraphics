package com.crystalgraphics.object;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgValueForm;

/**
 * A declared shape: an asset kind, a struct, an id list's element or a dispatch family, with its properties in the
 * order a file writes them. Mirrors Unreal's {@code UClass} and {@code UScriptStruct} as the reflection system
 * holds them: one declaration that serialization, inheritance, validation, undo, the editor and replication all read
 * (blueprint 01 §3.1–3.2).
 *
 * <p>A native type is declared by builder at init, never by annotations read through reflection:</p>
 *
 * <pre>{@code
 * public static final CgType SHAKE_PATTERNS = CgTypes.dispatch("crystalgraphics:shake_pattern", "kind").register();
 *
 * public static final CgType WAVE = CgTypes.struct("crystalgraphics:shake_pattern.wave")
 *         .property("frequency", CgValueType.FLOAT).quantity(CgQuantity.FREQUENCY)
 *         .register();
 * static { SHAKE_PATTERNS.member("wave", WAVE); }
 *
 * public static final CgType SHAKE = CgTypes.kind("crystalgraphics:shake").folder("cameras/shakes").version(1)
 *         .property("pattern", CgValueType.dispatch(SHAKE_PATTERNS)).required().flags(CgPropertyFlag.STRUCTURAL)
 *         .property("duration", CgValueType.FLOAT).quantity(CgQuantity.TIME).defaultValue(1.0)
 *         .register();
 * }</pre>
 *
 * <p>A data type (a module's inputs, a system's user parameters) is built the same way when its asset loads, and
 * {@link #freeze() frozen} once a definition compiles against it.</p>
 *
 * <ul>
 *   <li>A kind gets {@code _v}, {@code parent} and {@code units} before its own properties and {@code unsupported},
 *       {@code editor} and {@code remove} after them ({@code spec/core.md} § File); an element gets {@code id} first.
 *       Never declare those yourself. An element's merge keys ({@code enabled}, {@code after}, {@code before}) and
 *       its {@code editor} block differ by element, so each declares its own where its schema puts them.</li>
 *   <li>Each property modifier ({@code quantity}, {@code flags}…) applies to the property declared last.</li>
 *   <li>A frozen type refuses a new member or property: a change is structural for everything compiled against
 *       it, so it is a new type.</li>
 * </ul>
 */
public final class CgType {

    /** The keys every kind holds first and last, and every element first, in a file's order. */
    public static final String VERSION = "_v", PARENT = "parent", UNITS = "units", UNSUPPORTED = "unsupported",
            EDITOR = "editor", REMOVE = "remove", ID = "id";

    private final String id;
    private final CgTypeKind kind;
    private final String folder;
    private final int version;
    private final String dispatchKey;
    private final boolean dispatchInline;
    private final boolean open;
    private final List<CgProperty> properties;
    private final Map<String, CgProperty> byName;
    private final Map<String, CgType> members = new LinkedHashMap<>();
    private volatile boolean frozen;

    private CgType(Builder b) {
        this.id = b.id;
        this.kind = b.kind;
        this.folder = b.folder;
        this.version = b.version;
        this.dispatchKey = b.dispatchKey;
        this.dispatchInline = b.dispatchInline;
        this.open = b.open;
        List<CgProperty> props = new ArrayList<>(b.props.size());
        Map<String, CgProperty> names = new LinkedHashMap<>();
        for (int i = 0; i < b.props.size(); i++) {
            CgProperty p = b.props.get(i).build(this, i);
            props.add(p);
            names.put(p.name(), p);
        }
        this.properties = Collections.unmodifiableList(props);
        this.byName = Collections.unmodifiableMap(names);
    }

    /** {@code namespace:path}: {@code crystalgraphics:system}, {@code crystalgraphics:system.emitter}. */
    public String id() {
        return id;
    }

    /** Whether it is a kind, a struct, an element or a dispatch family. */
    public CgTypeKind kind() {
        return kind;
    }

    /** A kind's folder under {@code assets/<namespace>/} ({@code vfx/systems}); null for anything else. */
    public String folder() {
        return folder;
    }

    /** A kind's {@code _v}; 0 for anything else. */
    public int version() {
        return version;
    }

    /**
     * Whether it takes keys beyond those it declares, each a value in any form, written after them in the order
     * read: a sequence section's channels named by component or parameter ({@code x}, {@code _U0_LocX}). A key an
     * open type does not declare is a value, never {@code unsupported}.
     */
    public boolean open() {
        return open;
    }

    /** Its properties in declaration order: the order a file writes them. */
    public List<CgProperty> properties() {
        return properties;
    }

    /** The property named {@code name}, or null. */
    public CgProperty property(String name) {
        return byName.get(name);
    }

    /** A dispatch family's key: {@code kind}, {@code pattern}, {@code fn}; null for anything else. */
    public String dispatchKey() {
        return dispatchKey;
    }

    /**
     * Whether a member's fields sit beside the key ({@code { kind: perlin, amplitude: 2 }}) rather than under a key
     * of their own. An inline member may not declare a property named as the key.
     */
    public boolean dispatchInline() {
        return dispatchInline;
    }

    /** A dispatch family's members by key value, in registration order. */
    public Map<String, CgType> members() {
        return Collections.unmodifiableMap(members);
    }

    /** The member a family's key value names, or null. */
    public CgType member(String key) {
        return members.get(key);
    }

    /**
     * Adds a member to this dispatch family: the engine's at init, a mod's under its own namespace (P15).
     *
     * @throws IllegalStateException if the family is frozen, or {@code key} is taken
     * @throws IllegalArgumentException if this is not a family, or an inline member declares the key
     */
    public synchronized CgType member(String key, CgType member) {
        if (kind != CgTypeKind.DISPATCH) throw new IllegalArgumentException(id + " is not a dispatch family");
        if (frozen) throw new IllegalStateException(id + " is frozen: member " + key + " comes too late");
        if (dispatchInline && member.property(dispatchKey) != null) {
            throw new IllegalArgumentException(member.id() + " declares '" + dispatchKey + "', the key of " + id
                    + ", which keeps its members inline");
        }
        if (members.putIfAbsent(Objects.requireNonNull(key), Objects.requireNonNull(member)) != null) {
            throw new IllegalStateException(id + " already has a member '" + key + "'");
        }
        return this;
    }

    /** Whether a definition has compiled against it, so it no longer changes. */
    public boolean frozen() {
        return frozen;
    }

    /** Freezes it: no member is added after. Native types freeze with {@link CgTypes#freeze()}. */
    public void freeze() {
        frozen = true;
    }

    @Override
    public String toString() {
        return id;
    }

    // ── Building ─────────────────────────────────────────────────────────────────────────

    /** Declares a type. Start one from {@link CgTypes}. */
    public static final class Builder {

        private final String id;
        private final CgTypeKind kind;
        private final CgTypes registry;
        private String folder;
        private int version;
        private String dispatchKey;
        private boolean dispatchInline;
        private boolean open;
        private final List<PropertyBuilder> props = new ArrayList<>();
        private final List<Consumer<CgType>> onRegister = new ArrayList<>();
        private PropertyBuilder last;

        Builder(CgTypes registry, String id, CgTypeKind kind) {
            this.registry = registry;
            this.id = CgTypes.checkId(id);
            this.kind = kind;
            if (kind == CgTypeKind.KIND) {
                property(VERSION, CgValueType.INT).required();
                property(PARENT, CgValueType.STRING);
                property(UNITS, CgValueType.struct(CgTypes.UNITS));
            } else if (kind == CgTypeKind.ELEMENT) {
                property(ID, CgValueType.STRING).required();
            }
            last = null;
        }

        /** A kind's folder under {@code assets/<namespace>/}. */
        public Builder folder(String folder) {
            requireKind("folder");
            this.folder = Objects.requireNonNull(folder);
            return this;
        }

        /** A kind's current {@code _v}. */
        public Builder version(int version) {
            requireKind("version");
            if (version < 1) throw new IllegalArgumentException("a version starts at 1");
            this.version = version;
            return this;
        }

        /**
         * Takes keys beyond those declared, each a value in any form ({@code additionalProperties: true} in its
         * schema): channels named by data, a registered kind's own fields.
         */
        public Builder open() {
            if (kind == CgTypeKind.KIND || kind == CgTypeKind.DISPATCH) {
                throw new IllegalStateException(id + ": only a struct or an element is open");
            }
            this.open = true;
            return this;
        }

        Builder dispatchKey(String key, boolean inline) {
            this.dispatchKey = key;
            this.dispatchInline = inline;
            return this;
        }

        /** Declares the next property, in the order files write it. Modifiers after it apply to it. */
        public Builder property(String name, CgValueType type) {
            if (kind == CgTypeKind.DISPATCH) throw new IllegalStateException(id + ": a family's members hold the properties");
            for (PropertyBuilder p : props) {
                if (p.name.equals(name)) throw new IllegalArgumentException(id + " already declares '" + name + "'");
            }
            last = new PropertyBuilder(name, Objects.requireNonNull(type));
            props.add(last);
            return this;
        }

        /** What the last property's number measures ({@code spec/core.md} § Units). */
        public Builder quantity(CgQuantity quantity) {
            current().quantity = Objects.requireNonNull(quantity);
            return this;
        }

        /** Narrows the forms it accepts; a number otherwise takes every form ({@code spec/modules.md}). */
        public Builder forms(CgValueForm first, CgValueForm... rest) {
            current().forms = EnumSet.of(first, rest);
            return this;
        }

        /** The value with nothing written, as a file would write it. */
        public Builder defaultValue(Object value) {
            current().defaultValue = value;
            return this;
        }

        /** The last property must be present once the parent is merged in. */
        public Builder required() {
            current().required = true;
            return this;
        }

        /** Flags the last property. */
        public Builder flags(CgPropertyFlag first, CgPropertyFlag... rest) {
            current().flags.add(first);
            Collections.addAll(current().flags, rest);
            return this;
        }

        /** {@code ClampMin}/{@code ClampMax}; null leaves a side open. */
        public Builder range(Number min, Number max) {
            current().meta.min(toDouble(min)).max(toDouble(max));
            return this;
        }

        /** {@code UIMin}/{@code UIMax}; null leaves a side open. */
        public Builder uiRange(Number min, Number max) {
            current().meta.uiMin(toDouble(min)).uiMax(toDouble(max));
            return this;
        }

        /** The last property's tooltip. */
        public Builder description(String text) {
            current().meta.description(text);
            return this;
        }

        /** The last property's name in the editor, where its key will not do. */
        public Builder displayName(String name) {
            current().meta.displayName(name);
            return this;
        }

        /** The details panel section the last property sits in. */
        public Builder category(String category) {
            current().meta.category(category);
            return this;
        }

        /** The property this bool switches on ({@code InlineEditConditionToggle}). */
        public Builder toggleFor(String property) {
            current().meta.toggleFor(property);
            return this;
        }

        /** Shown only while {@code property} holds {@code value} ({@code EditCondition}, hiding). */
        public Builder visibleWhen(String property, Object value) {
            PropertyBuilder p = current();
            Map<String, Object> when = new LinkedHashMap<>(p.visibleWhen);
            when.put(property, value);
            p.visibleWhen = when;
            return this;
        }

        /** The lookups a curve on it may use; the first is its default. */
        public Builder by(String... lookups) {
            current().by = Collections.unmodifiableList(Arrays.asList(lookups.clone()));
            return this;
        }

        /** When a range on the last property rolls. */
        public Builder rolls(CgPropertyMeta.Rolls rolls) {
            current().meta.rolls(rolls);
            return this;
        }

        /** What an editor's timeline drags on the last property. */
        public Builder timeline(CgPropertyMeta.Timeline timeline) {
            current().meta.timeline(timeline);
            return this;
        }

        /** How a {@link CgPropertyFlag#NETWORKED} value reaches viewers; flags it networked. */
        public Builder replication(CgPropertyMeta.Replication mode) {
            current().meta.replication(mode);
            current().flags.add(CgPropertyFlag.NETWORKED);
            return this;
        }

        /**
         * Runs {@code then} with the type once it is registered: joining a family, say.
         *
         * <pre>{@code
         * CgTypes.element("mymod:renderer.arc").property("subdivisions", CgValueType.INT)
         *         .onRegister(t -> RENDERERS.member("mymod:arc", t))
         *         .register();
         * }</pre>
         */
        public Builder onRegister(Consumer<CgType> then) {
            onRegister.add(Objects.requireNonNull(then));
            return this;
        }

        /** Builds the type and adds it to the registry. */
        public CgType register() {
            if (kind == CgTypeKind.KIND) {
                if (folder == null || version == 0) throw new IllegalStateException(id + ": a kind needs a folder and a version");
                property(UNSUPPORTED, CgValueType.TREE);
                property(EDITOR, CgValueType.TREE).flags(CgPropertyFlag.EDITOR_ONLY);
                property(REMOVE, CgValueType.listOf(CgValueType.STRING));
            } else if (kind == CgTypeKind.ELEMENT && !declares(EDITOR)) {
                property(EDITOR, CgValueType.TREE).flags(CgPropertyFlag.EDITOR_ONLY);
            }
            CgType type = registry.add(new CgType(this));
            for (Consumer<CgType> then : onRegister) then.accept(type);
            return type;
        }

        private boolean declares(String name) {
            for (PropertyBuilder p : props) if (p.name.equals(name)) return true;
            return false;
        }

        private PropertyBuilder current() {
            if (last == null) throw new IllegalStateException(id + ": declare a property before its modifiers");
            return last;
        }

        private void requireKind(String what) {
            if (kind != CgTypeKind.KIND) throw new IllegalStateException(id + ": only a kind has a " + what);
        }

        private static Double toDouble(Number n) {
            return n == null ? null : n.doubleValue();
        }
    }

    private static final class PropertyBuilder {
        final String name;
        final CgValueType type;
        CgQuantity quantity = CgQuantity.NONE;
        Set<CgValueForm> forms;
        Object defaultValue;
        boolean required;
        final Set<CgPropertyFlag> flags = EnumSet.noneOf(CgPropertyFlag.class);
        final CgPropertyMeta.CgPropertyMetaBuilder meta = CgPropertyMeta.builder();
        Map<String, Object> visibleWhen = Collections.emptyMap();
        List<String> by = Collections.emptyList();

        PropertyBuilder(String name, CgValueType type) {
            this.name = name;
            this.type = type;
        }

        CgProperty build(CgType owner, int index) {
            // A number takes every form unless narrowed; anything else is written as itself.
            Set<CgValueForm> accepted = forms != null ? forms
                    : type.takesEveryForm() ? CgValueForm.ALL
                    : EnumSet.of(CgValueForm.CONSTANT);
            CgPropertyMeta m = meta.visibleWhen(Collections.unmodifiableMap(visibleWhen)).by(by).build();
            return new CgProperty(owner, index, name, type, quantity, accepted, defaultValue, required, flags,
                    m.equals(CgPropertyMeta.NONE) ? CgPropertyMeta.NONE : m);
        }
    }
}
