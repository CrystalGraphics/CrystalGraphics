package com.crystalgraphics.asset.kind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgPropertyMeta;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgValueForm;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFormats;
import com.crystalgraphics.util.io.CgIO;

/**
 * The built-in modules' declarations, read from {@code modules.yaml} into one type per module: its inputs as
 * properties a module entry's {@code inputs} are checked and decoded against. Niagara's module scripts are data,
 * reflected like native types; these are ours.
 *
 * <pre>{@code
 * CgModuleTypes modules = CgModuleTypes.builtIn();                     // the engine's, loaded once
 * CgModuleTypes.Module drag = modules.get("crystalgraphics:drag");
 * drag.inputs().property("drag").quantity();                          // RATE
 * drag.groups();                                                      // [particle_update]
 * }</pre>
 *
 * <ul>
 *   <li>A module's id may be written with or without {@code crystalgraphics:}; built-ins are the engine's.</li>
 *   <li>An input whose type, quantity or form the format does not name fails the load, naming the module and input.</li>
 *   <li>Each module's inputs type is a struct {@code crystalgraphics:module.<id>}, registered in the registry given.</li>
 * </ul>
 */
public final class CgModuleTypes {

    /** Where the engine ships its copy of the format's {@code modules.yaml}. */
    public static final String RESOURCE = "crystalgraphics:vfx/modules.yaml";

    /** A {@code crystalgraphics:} module the catalogue does not have. */
    public static final String UNKNOWN_MODULE = "unknown_module";
    /** An input its module does not declare. */
    public static final String UNKNOWN_INPUT = "unknown_input";

    private static volatile CgModuleTypes builtIn;

    /**
     * A module's declaration: where it may sit in a stack, and its inputs.
     *
     * @param id       {@code crystalgraphics:drag}
     * @param groups   the stacks it may sit in: {@code emitter_update}, {@code particle_spawn}, {@code particle_update}
     * @param order    where in its stack it runs ({@code forces}, {@code spawn_place}…); null where the format gives none
     * @param once     at most one per stack
     * @param analytic allowed on an {@code analytic} emitter: a closed form of seed and age
     * @param inputs   its inputs as a struct's properties, in declaration order
     */
    public record Module(String id, List<String> groups, String order, boolean once, boolean analytic, CgType inputs) {
    }

    private final Map<String, Module> modules;

    private CgModuleTypes(Map<String, Module> modules) {
        this.modules = Collections.unmodifiableMap(modules);
    }

    /** The engine's built-in modules, read from {@link #RESOURCE} into the engine's registry once. */
    public static CgModuleTypes builtIn() {
        CgModuleTypes m = builtIn;
        if (m != null) return m;
        synchronized (CgModuleTypes.class) {
            if (builtIn == null) {
                String yaml = CgIO.loadSource(RESOURCE);
                if (yaml == null) throw new IllegalStateException(RESOURCE + " is missing from the engine's jar");
                builtIn = load(yaml, CgTypes.global());
            }
            return builtIn;
        }
    }

    /** Reads {@code modules.yaml}'s text into types registered in {@code registry}. */
    public static CgModuleTypes load(String yaml, CgTypes registry) {
        Map<String, Object> doc = asMap(CgFormats.YAML.fromText(CgCodecs.PLAIN, yaml), "modules.yaml");
        Map<String, Module> out = new LinkedHashMap<>();
        for (Object entry : asList(doc.get("modules"), "modules")) {
            Map<String, Object> m = asMap(entry, "a module");
            String id = qualified(string(m.get("id"), "a module's id"));
            CgType.Builder b = registry.declare("crystalgraphics:module." + id.substring(id.indexOf(':') + 1), CgTypeKind.STRUCT);
            for (Object input : asList(m.getOrDefault("inputs", Collections.emptyList()), id + ".inputs")) {
                declare(b, id, asMap(input, id + "'s input"));
            }
            List<String> groups = new ArrayList<>();
            for (Object g : asList(m.getOrDefault("groups", Collections.emptyList()), id + ".groups")) groups.add(string(g, id + ".groups"));
            Module module = new Module(id, Collections.unmodifiableList(groups), (String) m.get("order"),
                    Boolean.TRUE.equals(m.get("once")), Boolean.TRUE.equals(m.get("analytic")), b.register());
            if (out.put(id, module) != null) throw new IllegalArgumentException("modules.yaml declares " + id + " twice");
        }
        return new CgModuleTypes(out);
    }

    /** The module {@code id} ({@code drag} or {@code crystalgraphics:drag}), or null. */
    public Module get(String id) {
        return modules.get(qualified(id));
    }

    /** Every module, in {@code modules.yaml}'s order. */
    public List<Module> all() {
        return new ArrayList<>(modules.values());
    }

    // ── One input ────────────────────────────────────────────────────────────────────────

    private static void declare(CgType.Builder b, String module, Map<String, Object> in) {
        String name = string(in.get("name"), module + ": an input's name");
        String where = module + "." + name;
        b.property(name, valueType(in, where));
        Object quantity = in.get("quantity");
        if (quantity != null) {
            CgQuantity q = CgQuantity.bySpecName(string(quantity, where + ".quantity"));
            if (q == null) throw new IllegalArgumentException(where + ": no quantity '" + quantity + "'");
            b.quantity(q);
        }
        Object forms = in.get("forms");
        if (forms != null) {
            List<Object> names = asList(forms, where + ".forms");
            CgValueForm[] rest = new CgValueForm[names.size() - 1];
            for (int i = 1; i < names.size(); i++) rest[i - 1] = form(names.get(i), where);
            b.forms(form(names.get(0), where), rest);
        }
        if (in.containsKey("default")) b.defaultValue(in.get("default"));
        Object by = in.get("by");
        if (by != null) {
            List<Object> lookups = asList(by, where + ".by");
            String[] each = new String[lookups.size()];
            for (int i = 0; i < each.length; i++) each[i] = string(lookups.get(i), where + ".by");
            b.by(each);
        }
        if (in.get("rolls") != null) b.rolls(enumOf(CgPropertyMeta.Rolls.class, in.get("rolls"), where + ".rolls"));
        if (in.get("timeline") != null) b.timeline(enumOf(CgPropertyMeta.Timeline.class, in.get("timeline"), where + ".timeline"));
        if (Boolean.TRUE.equals(in.get("per_instance"))) b.flags(CgPropertyFlag.PER_INSTANCE);
        if (in.get("toggle_for") != null) b.toggleFor(string(in.get("toggle_for"), where + ".toggle_for"));
        Object visible = in.get("visible_when");
        if (visible != null) {
            for (Map.Entry<String, Object> e : asMap(visible, where + ".visible_when").entrySet()) b.visibleWhen(e.getKey(), e.getValue());
        }
        if (in.get("description") != null) b.description(string(in.get("description"), where + ".description"));
    }

    private static CgValueType valueType(Map<String, Object> in, String where) {
        String type = string(in.get("type"), where + ".type");
        switch (type) {
            case "float": return CgValueType.FLOAT;
            case "int": return CgValueType.INT;
            case "bool": return CgValueType.BOOL;
            case "string": return CgValueType.STRING;
            case "vec2": return CgValueType.VEC2;
            case "vec3": return CgValueType.VEC3;
            case "vec4": return CgValueType.VEC4;
            case "colour": return CgValueType.COLOUR;
            case "rotation": return CgValueType.ROTATION;
            case "position": return CgValueType.POSITION;
            case "curve": return CgValueType.CURVE;
            case "mesh": return CgValueType.MESH;
            case "texture": return CgValueType.TEXTURE;
            case "material": return CgCommonStructs.hard("material");
            case "socket": return CgValueType.struct(CgCommonStructs.SOCKET);
            case "field": return CgValueType.FIELD;
            case "array": return CgValueType.ARRAY;
            case "struct": return CgValueType.OPEN_STRUCT;
            case "enum": {
                List<Object> values = asList(in.get("values"), where + ".values");
                String[] each = new String[values.size()];
                for (int i = 0; i < each.length; i++) each[i] = string(values.get(i), where + ".values");
                return CgValueType.enumOf(each);
            }
            default:
                throw new IllegalArgumentException(where + ": no input type '" + type + "'");
        }
    }

    private static CgValueForm form(Object name, String where) {
        CgValueForm f = CgValueForm.bySpecName(string(name, where + ".forms"));
        if (f == null) throw new IllegalArgumentException(where + ": no form '" + name + "'");
        return f;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Object name, String where) {
        try {
            return Enum.valueOf(type, string(name, where).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": no " + type.getSimpleName().toLowerCase(Locale.ROOT) + " '" + name + "'");
        }
    }

    // ── The tree ─────────────────────────────────────────────────────────────────────────

    private static String qualified(String id) {
        return id.indexOf(':') >= 0 ? id : "crystalgraphics:" + id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String what) {
        if (!(o instanceof Map)) throw new IllegalArgumentException(what + " is not a map");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o, String what) {
        if (!(o instanceof List)) throw new IllegalArgumentException(what + " is not a list");
        return (List<Object>) o;
    }

    private static String string(Object o, String what) {
        if (!(o instanceof String)) throw new IllegalArgumentException(what + " is not text: " + o);
        return (String) o;
    }
}
