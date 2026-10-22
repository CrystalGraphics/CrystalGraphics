package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.material.CgMaterialArchetypes;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.render.world.CgWorldLights;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.look.CgVfxLayout;
import com.crystalgraphics.vfx.look.CgVfxParam;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a native behaviour's slot draws: a material on the slot, fed by user parameters, or a light where the slot
 * would draw. A system's {@code slots:} entry, or one built in Java into a look or a kit; the particle renderers'
 * counterpart for what an effect's own code places (a beam's body, a charge ball, a trail).
 *
 * <pre>{@code
 * // In Java: the same tree a system file's slots: entry holds
 * CgVfxSlotRenderer shell = CgVfxSlotRenderer.of("l1", CgBeamBody.BODY).material(SHELL_MATERIAL)
 *         .bind("_Shell", "user.shell").size(1.15f).build();
 * CgVfxLook look = CgEnergyWave.kamehameha().derive().slot(shell).build();
 *
 * // From a file:   slots: [ { id: l1, slot: crystalgraphics:wave/body, material: mymod:materials/shell,
 * //                           bind: { _Shell: user.shell }, size: 1.15 } ]
 * CgVfxSlotRenderer loaded = CgVfxSlotRenderer.compile("mymod:wave", entry, deps);
 * }</pre>
 *
 * <p>A light in place of a material, as Niagara's light renderer adds its system's lights: Unreal's point or spot light,
 * added to the world's light list ({@link CgWorldLights}) each frame the slot draws.
 *
 * <pre>{@code
 * CgVfxSlotRenderer glow = CgVfxSlotRenderer.light("light", CgVfxBlast.LIGHT)
 *         .lightColor(0xFFFFC080, 1f).intensity(4f).attenuationRadius(2.2f)
 *         .bind("light_color", GLOW)                      // bound by the light's own field names
 *         .build();
 *
 * // From a file:   - id: light
 * //                  slot: crystalgraphics:blast/light
 * //                  light: { light_color: { argb: 0xFFFFC080 }, intensity: 4, attenuation_radius: 2.2 }
 * //                  bind: { light_color: user.glow }
 *
 * static final CgVfxSlot LAMP = CgVfxSlot.mesh("mymod:bolt/lamp");
 * CgVfxSlotRenderer lamp = CgVfxSlotRenderer.light("lamp", LAMP)
 *         .spot(10f, 25f)                                 // along the slot's +Y: full within 10 degrees, none past 25
 *         .intensity(40f).attenuationRadius(30f)
 *         .sourceRadius(0.5f)                             // a ball, so a surface it touches is lit
 *         .build();
 * // From a file:   light: { type: spot, inner_cone_angle: 10, outer_cone_angle: 25, intensity: 40,
 * //                         attenuation_radius: 30, source_radius: 0.5 }
 * }</pre>
 *
 * <ul>
 *   <li>Its slot is found by name among the slots the engine's and a mod's behaviours declare; an unknown one fails.</li>
 *   <li>A tube slot draws the geometry its material's archetype names ({@code Tags { "Tube" = "Volume" | "Closed" }});
 *       a Tube-tagged archetype on another slot fails at load, unless it draws tubes as a variant
 *       ({@code #pragma cg_feature FX_ON_TUBE}) beside its own kind.</li>
 *   <li>Binds read user parameters alone, as a particle renderer's do; {@code size} is times the slot's own.</li>
 *   <li>Drawn in the look's order of its slot renderers, then its material's {@code order}; after its effect's
 *       distortion unless its material has a Distortion pass.</li>
 *   <li>A light takes no {@code material}, {@code size} or {@code mesh}: both a material and a light, or neither, fails
 *       at load. Only mesh and tube slots take one; how each places it is {@link Light}. A tube slot's is its chain of
 *       tubes: a spot or a {@code source_length} there fails at load, and so do cone angles on a point.</li>
 * </ul>
 */
public final class CgVfxSlotRenderer {

    private final String id;
    private final CgVfxSlot slot;
    @Nullable private final String material;
    @Nullable private final CgMaterialDefinition definition;
    @Nullable private final Light light;
    private final Map<String, Object> bind;
    private final float size;
    @Nullable private final String meshPath;
    @Nullable private final CgQuality quality;
    private final Map<String, Object> tree;

    private CgVfxSlotRenderer(String id, CgVfxSlot slot, @Nullable String material, @Nullable CgMaterialDefinition definition,
                              @Nullable Light light, Map<String, Object> bind, float size, @Nullable String meshPath,
                              @Nullable CgQuality quality, Map<String, Object> tree) {
        this.id = id;
        this.slot = slot;
        this.material = material;
        this.definition = definition;
        this.light = light;
        this.bind = bind;
        this.size = size;
        this.meshPath = meshPath;
        this.quality = quality;
        this.tree = tree;
    }

    /** A slot renderer of id {@code id} on {@code slot}, built as its file entry would read. */
    public static Builder of(String id, CgVfxSlot slot) {
        return new Builder(id, slot, false);
    }

    /** A light of id {@code id} on {@code slot}, a mesh or tube slot: white, intensity 1, reaching the slot's size. */
    public static Builder light(String id, CgVfxSlot slot) {
        return new Builder(id, slot, true);
    }

    /**
     * {@code system}'s {@code slots:} entry; {@code deps} answers its material, a hard reference its asset compiled
     * first, null leaving {@link #definition()} to the caller's ask.
     *
     * @throws IllegalArgumentException naming the entry: an unknown slot, a mesh on a slot that takes none, a bind that
     *                                  reads no user parameter, a Tube-tagged material off a tube slot, a material and a
     *                                  light or neither, a light on a slot that takes none
     */
    public static CgVfxSlotRenderer compile(String system, Map<?, ?> entry, @Nullable CgAssetCompiler.Dependencies deps) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : entry.entrySet()) tree.put(String.valueOf(e.getKey()), e.getValue());
        String who = system + "'s slot " + tree.get("id");
        if (!(tree.get("id") instanceof String id)) throw new IllegalArgumentException(who + " has no id");
        if (!(tree.get("slot") instanceof String name)) throw new IllegalArgumentException(who + " names no slot");
        CgVfxSlot slot = CgVfxSlot.named(name);
        if (slot == null) throw new IllegalArgumentException(who + " draws on slot " + name + ", which no behaviour declares");
        Object lightTree = tree.get("light");
        Object materialName = tree.get("material");
        if (lightTree != null && materialName != null) {
            throw new IllegalArgumentException(who + " has both a material and a light: a slot draws one or the other");
        }
        Light light = null;
        String material = null;
        if (lightTree != null) {
            if (!(lightTree instanceof Map<?, ?> fields)) throw new IllegalArgumentException(who + "'s light is not a map");
            if (slot.kind() != CgVfxSlot.Kind.MESH && slot.kind() != CgVfxSlot.Kind.TUBE) {
                throw new IllegalArgumentException(who + " gives a light to " + slot + ": only mesh and tube slots take one");
            }
            for (String replaced : List.of("size", "mesh")) {
                if (tree.containsKey(replaced)) {
                    throw new IllegalArgumentException(who + " gives a light a " + replaced + ": its attenuation_radius is its reach");
                }
            }
            light = Light.compile(who, fields);
        } else if (materialName instanceof String m) {
            material = m;
        } else {
            throw new IllegalArgumentException(who + " names no material asset and no light");
        }
        CgMaterialDefinition definition = null;
        if (material != null && deps != null) {
            Object d = deps.definition(CgLookKinds.MATERIAL, material);
            if (d instanceof CgMaterialDefinition md) definition = md;
            else if (d != null) throw new IllegalArgumentException(who + "'s material " + material + " is not a material");
        }
        if (definition != null && definition.tube() != null && slot.kind() != CgVfxSlot.Kind.TUBE
                && !CgMaterialArchetypes.FILES.parse(definition.permutation().shader()).featureNames()
                        .contains(CgVfxSlot.Kind.TUBE.keyword())) {
            throw new IllegalArgumentException(who + " draws " + material + ", whose archetype needs a tube (\"Tube\" = \""
                    + definition.tube() + "\"), on " + slot);
        }
        Map<String, Object> bind = new LinkedHashMap<>();
        if (tree.get("bind") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> b : m.entrySet()) {
                Object form = b.getValue();
                String from = String.valueOf(form instanceof Map<?, ?> f ? f.get("bind") : form);
                if (!from.startsWith("user.")) {
                    throw new IllegalArgumentException(who + " binds " + b.getKey() + " to " + from + ": a bind reads a user"
                            + " parameter, user.<name>");
                }
                if (light != null && !Light.FIELDS.contains(String.valueOf(b.getKey()))) {
                    throw new IllegalArgumentException(who + " binds " + b.getKey() + ", which its light has not: it binds "
                            + Light.FIELDS);
                }
                bind.put(String.valueOf(b.getKey()), form);
            }
        }
        if (light != null) {
            boolean cone = bind.containsKey("inner_cone_angle") || bind.containsKey("outer_cone_angle");
            if (cone && !light.spot()) throw new IllegalArgumentException(who + " binds a cone angle of a point: type: spot");
            if (slot.kind() == CgVfxSlot.Kind.TUBE && (light.spot() || light.sourceLength() > 0f
                    || bind.containsKey("source_length"))) {
                throw new IllegalArgumentException(who + "'s light on " + slot + " is its chain of tubes: no spot, no source_length");
            }
        }
        float size = tree.get("size") instanceof Number n ? n.floatValue() : 1f;
        String meshPath = null;
        if (tree.get("mesh") instanceof String path) {
            if (slot.kind() != CgVfxSlot.Kind.MESH) throw new IllegalArgumentException(who + " gives a mesh to " + slot + ", which takes none");
            meshPath = path;
        } else if (tree.get("mesh") != null) {
            throw new IllegalArgumentException(who + "'s mesh is a path; a bound mesh is not built yet");
        }
        CgQuality quality = tree.get("quality") instanceof String q ? CgQuality.valueOf(q.toUpperCase(Locale.ROOT)) : null;
        return new CgVfxSlotRenderer(id, slot, material, definition, light, Collections.unmodifiableMap(bind), size,
                meshPath, quality, Collections.unmodifiableMap(tree));
    }

    public String id() {
        return id;
    }

    public CgVfxSlot slot() {
        return slot;
    }

    /** Its material asset's id; null for a light. */
    @Nullable
    public String material() {
        return material;
    }

    /** Its material compiled; null for a light, or where it compiled with no dependencies to ask. */
    @Nullable
    public CgMaterialDefinition definition() {
        return definition;
    }

    /** Its light; null where it draws a material. */
    @Nullable
    public Light light() {
        return light;
    }

    /**
     * Material parameter, or a light's field, to the user parameter it reads per draw, {@code user.<name>} or
     * {@code { bind, scale }}.
     */
    public Map<String, Object> bind() {
        return bind;
    }

    /** Times its slot's own size; 1 for a light. */
    public float size() {
        return size;
    }

    /** The tier it draws from; null for every tier. */
    @Nullable
    public CgQuality quality() {
        return quality;
    }

    /** The tube geometry its material's archetype needs; null for the chunked tube or no definition. */
    @Nullable
    public CgParsedShader.Tube tube() {
        return definition == null ? null : definition.tube();
    }

    /** Its mesh on a mesh slot, loaded at its first ask and kept; null for the slot's sphere. */
    @Nullable
    public CgMeshLods mesh() {
        return meshPath == null ? null : CgMeshLoader.lods(meshPath);
    }

    /** The entry as its file holds it. */
    public Map<String, Object> tree() {
        return tree;
    }

    @Override
    public String toString() {
        return "slot renderer " + id + " on " + slot + " (" + (light != null ? "light" : material) + ")";
    }

    /**
     * A slot's light, Unreal's point light ({@code UPointLightComponent}'s {@code LightColor}, {@code Intensity},
     * {@code AttenuationRadius}, {@code SourceRadius} and {@code SourceLength}) or spot light ({@code USpotLightComponent}'s
     * {@code InnerConeAngle} and {@code OuterConeAngle}, half angles in degrees) with inverse-squared falloff, its colour
     * linear. Where the slot places it:
     * <ul>
     *   <li><b>A mesh slot</b>: at the draw's point, its size {@code s} the transform's x scale (the placed sphere's
     *       radius), its colour times the draw's third number (its strength, {@code ez}). A spot points, and a source
     *       line runs, along the transform's +Y, the engine's shape axis.</li>
     *   <li><b>A tube slot</b>: a chain of tube lights (Unreal's {@code SourceLength}), one per stretch of half a reach,
     *       chord to chord along the path, {@code s} each stretch's end ring's radius, weighted by the stretch's length
     *       over {@code s}; its reach measured past the stretch's ends. No spot.</li>
     * </ul>
     * Its source radius and length are times {@code s}, as its reach is.
     * Its reach is {@code attenuationRadius × s}. Its colour is {@code lightColor × intensity × s²} times the
     * effect's presence: under the inverse square in blocks, that is {@code lightColor × intensity}
     * where the light is {@code s} away, falling off with the square of the distance past it, so a slot's light grows
     * with what it lights from.
     *
     * @param red   {@code light_color}'s, linear, times its alpha
     * @param green {@code light_color}'s, linear, times its alpha
     * @param blue  {@code light_color}'s, linear, times its alpha
     */
    public record Light(float red, float green, float blue, float intensity, float attenuationRadius, float sourceRadius,
                        float sourceLength, boolean spot, float innerConeAngle, float outerConeAngle, boolean castShadows) {

        /** The fields a light binds by, in the order a resolved light reads them. */
        public static final List<String> FIELDS = List.of("light_color", "intensity", "attenuation_radius", "source_radius",
                "source_length", "inner_cone_angle", "outer_cone_angle");

        static Light compile(String who, Map<?, ?> fields) {
            for (Object key : fields.keySet()) {
                if (!"type".equals(key) && !"cast_shadows".equals(key) && !FIELDS.contains(String.valueOf(key))) {
                    throw new IllegalArgumentException(who + "'s light has " + key + ": a light has type, cast_shadows and "
                            + FIELDS);
                }
            }
            Object type = fields.get("type");
            boolean spot = "spot".equals(type);
            if (type != null && !spot && !"point".equals(type)) {
                throw new IllegalArgumentException(who + "'s light is a " + type + ": a point or a spot");
            }
            if (!spot && (fields.containsKey("inner_cone_angle") || fields.containsKey("outer_cone_angle"))) {
                throw new IllegalArgumentException(who + "'s light has cone angles but is a point: type: spot");
            }
            float[] c = fields.get("light_color") == null ? new float[] {1f, 1f, 1f}
                    : linear(who, fields.get("light_color"));
            Object shadows = fields.get("cast_shadows");
            if (shadows != null && !(shadows instanceof Boolean)) {
                throw new IllegalArgumentException(who + "'s light's cast_shadows is " + shadows + ", not true or false");
            }
            return new Light(c[0], c[1], c[2], number(who, fields, "intensity", 1f), number(who, fields, "attenuation_radius", 1f),
                    number(who, fields, "source_radius", 0f), number(who, fields, "source_length", 0f), spot,
                    number(who, fields, "inner_cone_angle", 0f), number(who, fields, "outer_cone_angle", 44f),
                    shadows == null || (Boolean) shadows);
        }

        private static float number(String who, Map<?, ?> fields, String name, float fallback) {
            Object v = fields.get(name);
            if (v == null) return fallback;
            if (v instanceof Number n) return n.floatValue();
            throw new IllegalArgumentException(who + "'s light's " + name + " is " + v + ", not a number");
        }

        /**
         * A colour as a linear reader takes it: {@code { argb, intensity }} decoded from sRGB, then times its intensity;
         * floats as they are. Either times its alpha.
         */
        private static float[] linear(String who, Object value) {
            if (value instanceof Map<?, ?> m && m.get("argb") instanceof Number argbN) {
                int argb = (int) argbN.longValue();
                float i = m.get("intensity") instanceof Number n ? n.floatValue() : 1f;
                float a = (argb >>> 24) / 255f;
                return new float[] {CgWorldLights.linear((argb >> 16 & 0xFF) / 255f) * i * a,
                        CgWorldLights.linear((argb >> 8 & 0xFF) / 255f) * i * a, CgWorldLights.linear((argb & 0xFF) / 255f) * i * a};
            }
            if (value instanceof List<?> v && v.size() >= 3 && v.size() <= 4) {
                float[] c = new float[4];
                c[3] = 1f;
                for (int k = 0; k < v.size(); k++) {
                    if (!(v.get(k) instanceof Number n)) throw new IllegalArgumentException(who + "'s light_color is " + value);
                    c[k] = n.floatValue();
                }
                return new float[] {c[0] * c[3], c[1] * c[3], c[2] * c[3]};
            }
            throw new IllegalArgumentException(who + "'s light_color is " + value + ": { argb, intensity } or 3 to 4 floats");
        }
    }

    /** Writes a {@code slots:} entry as a file would hold it, then compiles it. */
    public static final class Builder {
        private final Map<String, Object> tree = new LinkedHashMap<>();
        private final Map<String, Object> bind = new LinkedHashMap<>();
        @Nullable private final Map<String, Object> light;
        private CgMaterialDefinition definition;

        private Builder(String id, CgVfxSlot slot, boolean light) {
            tree.put("id", id);
            tree.put("slot", slot.name());
            this.light = light ? new LinkedHashMap<>() : null;
        }

        /** Its material, compiled: its id is written. */
        public Builder material(CgMaterialDefinition material) {
            definition = material;
            tree.put("material", material.id());
            return this;
        }

        /** A light's colour: {@code 0xAARRGGBB} in sRGB, decoded to linear, times {@code intensity} and its alpha. */
        public Builder lightColor(int argb, float intensity) {
            Map<String, Object> colour = new LinkedHashMap<>();
            colour.put("argb", (long) argb & 0xFFFFFFFFL);
            if (intensity != 1f) colour.put("intensity", (double) intensity);
            return light("light_color", colour);
        }

        /** A light's brightness at its slot's size from it, times its colour. */
        public Builder intensity(float intensity) {
            return light("intensity", (double) intensity);
        }

        /** A light's reach, times its slot's size. */
        public Builder attenuationRadius(float times) {
            return light("attenuation_radius", (double) times);
        }

        /**
         * A spot along the slot's +Y: full within {@code innerDegrees} of it, nothing past {@code outerDegrees} (half
         * angles, Unreal's InnerConeAngle and OuterConeAngle). Mesh slots only.
         */
        public Builder spot(float innerDegrees, float outerDegrees) {
            light("type", "spot");
            light("inner_cone_angle", (double) innerDegrees);
            return light("outer_cone_angle", (double) outerDegrees);
        }

        /** Its source a ball this many times its slot's size across from its centre (Unreal's SourceRadius). */
        public Builder sourceRadius(float times) {
            return light("source_radius", (double) times);
        }

        /** Its source a line this many times its slot's size long, along the slot's +Y (Unreal's SourceLength). Mesh slots only. */
        public Builder sourceLength(float times) {
            return light("source_length", (double) times);
        }

        /** Whether blocks and entities in its way withhold it (Unreal's bCastShadows): true unless set. */
        public Builder castShadows(boolean cast) {
            return light("cast_shadows", cast);
        }

        private Builder light(String field, Object value) {
            if (light == null) throw new IllegalStateException(field + " is a light's: build it with CgVfxSlotRenderer.light");
            light.put(field, value);
            return this;
        }

        /**
         * Material parameter {@code parameter}, or a light's field, read per draw from user parameter {@code source},
         * {@code user.<name>}.
         */
        public Builder bind(String parameter, String source) {
            bind.put(parameter, source);
            return this;
        }

        /** Material parameter {@code parameter}, or a light's field, read per draw from {@code param}, bound by its full name. */
        public Builder bind(String parameter, CgVfxParam param) {
            return bind(parameter, "user." + CgVfxLayout.fullName(param));
        }

        /** Material parameter {@code parameter}, or a light's field, read per draw from {@code param} times {@code scale}. */
        public Builder bind(String parameter, CgVfxParam param, float scale) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("bind", "user." + CgVfxLayout.fullName(param));
            form.put("scale", (double) scale);
            bind.put(parameter, form);
            return this;
        }

        /** Times its slot's own size. */
        public Builder size(float size) {
            tree.put("size", (double) size);
            return this;
        }

        /** A mesh slot's mesh, by path or {@link CgMeshLoader#provide}d id, in place of the sphere. */
        public Builder mesh(String path) {
            tree.put("mesh", path);
            return this;
        }

        /** Drawn from {@code quality} up. */
        public Builder quality(CgQuality quality) {
            tree.put("quality", quality.name().toLowerCase(Locale.ROOT));
            return this;
        }

        /** The slot renderer, compiled from the entry written, in the declaration's key order. */
        public CgVfxSlotRenderer build() {
            Map<String, Object> entry = new LinkedHashMap<>();
            for (String key : List.of("id", "slot", "material")) if (tree.containsKey(key)) entry.put(key, tree.get(key));
            if (light != null) {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (String key : List.of("type", "light_color", "intensity", "attenuation_radius", "source_radius",
                        "source_length", "inner_cone_angle", "outer_cone_angle", "cast_shadows")) {
                    if (light.containsKey(key)) fields.put(key, light.get(key));
                }
                entry.put("light", fields);
            }
            if (!bind.isEmpty()) entry.put("bind", new LinkedHashMap<>(bind));
            for (String key : List.of("size", "mesh", "quality")) if (tree.containsKey(key)) entry.put(key, tree.get(key));
            CgMaterialDefinition d = definition;
            return compile("java", entry, d == null ? null : (kind, id) -> id.equals(d.id()) ? d : null);
        }
    }
}
