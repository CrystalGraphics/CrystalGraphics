package com.crystalgraphics.vfx.particle.renderer;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.look.CgVfxFlipbook;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties.Common;
import com.crystalgraphics.vfx.look.CgVfxLayout;
import com.crystalgraphics.vfx.look.CgVfxParam;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An emitter's renderers, compiled from its file's {@code renderers:} and built in Java to the same tree.
 *
 * <pre>{@code
 * // From a file: each entry of an emitter's renderers:
 * List<CgVfxRendererProperties> renderers = CgVfxRenderers.compileAll("sparks", tree.get("renderers"));
 *
 * // In Java: the builder writes the entry a file would hold, then compiles it
 * CgVfxRendererProperties sprite = CgVfxRenderers.sprite("r1").material("crystalgraphics:materials/spark")
 *         .bind("_Color", "user.ember").value("_Soft", 0.5).alignment("velocity").build();
 * CgVfxRendererProperties tails = CgVfxRenderers.trails("r2").material("crystalgraphics:materials/streaks")
 *         .field("points", 12).build();
 * }</pre>
 *
 * <ul>
 *   <li>Built in Java or read from a file, a renderer is its tree: {@link CgVfxRendererProperties#tree()} writes
 *       back exactly what compiled it.</li>
 *   <li>{@code system}, {@code anim_trail}, {@code beam} and {@code light} fail at compile, naming what they need;
 *       so does a material named by a user parameter ({@code material: { bind: user.x }}).</li>
 *   <li>{@code bind} takes user parameters alone: a particle's own attributes are read in the material
 *       ({@code cg_dynamic_parameter}, {@code CG_PARTICLE_COLOR}), as Unreal's material graph reads them.</li>
 *   <li>A flipbook's {@code mode: direct} fails too: no module sets its frame yet.</li>
 * </ul>
 */
public final class CgVfxRenderers {

    private static final String[] COMMON_KEYS = {"enabled", "quality", "sort_order_hint", "motion_vectors", "material",
            "bind", "values", "keywords", "dynamic_parameter_index", "macro_uv"};

    private CgVfxRenderers() {
    }

    /** Every entry of an emitter's {@code renderers:}, in order, their materials by id alone. */
    public static List<CgVfxRendererProperties> compileAll(String emitter, @Nullable Object renderers) {
        return compileAll(emitter, renderers, null);
    }

    /**
     * Every entry of an emitter's {@code renderers:}, in order; an absent list is empty. {@code deps} answers each
     * renderer's material, a hard reference its asset compiled first; null leaves {@link Common#definition()} null.
     */
    public static List<CgVfxRendererProperties> compileAll(String emitter, @Nullable Object renderers,
                                                           @Nullable CgAssetCompiler.Dependencies deps) {
        List<CgVfxRendererProperties> out = new ArrayList<>();
        if (renderers instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> m)) throw new IllegalArgumentException(emitter + ": a renderer is a map, not " + entry);
                out.add(compile(emitter, m, deps));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** One {@code renderers:} entry of {@code emitter}, its material by id alone. */
    public static CgVfxRendererProperties compile(String emitter, Map<?, ?> entry) {
        return compile(emitter, entry, null);
    }

    /** One {@code renderers:} entry of {@code emitter}; {@code deps} answers its material. */
    public static CgVfxRendererProperties compile(String emitter, Map<?, ?> entry,
                                                  @Nullable CgAssetCompiler.Dependencies deps) {
        Map<String, Object> tree = copy(entry);
        String kind = String.valueOf(tree.get("kind"));
        String who = emitter + "'s renderer " + tree.get("id");
        Common common = common(who, tree, deps);
        CgVfxRendererProperties r = switch (kind) {
            case "sprite" -> new CgVfxSpriteRendererProperties(common, tree,
                    choice(who, tree, "alignment", CgVfxSpriteRendererProperties.Alignment.class, "unaligned"),
                    choice(who, tree, "facing", CgVfxSpriteRendererProperties.Facing.class, "camera"),
                    floats(tree.get("facing_blend"), 2), sort(who, tree),
                    tree.get("pivot") != null ? vector(who, "pivot", tree.get("pivot"), 2) : new float[] {0.5f, 0.5f},
                    flipbook(who, tree.get("sub_image")), floats(tree.get("camera_distance"), 2),
                    Boolean.TRUE.equals(tree.get("cast_shadows")),
                    tree.get("soft_depth") instanceof Number n ? n.floatValue() : Float.NaN);
            case "mesh" -> mesh(who, common, tree, deps);
            case "ribbon" -> ribbon(who, common, tree);
            case "arc" -> new CgVfxArcRendererProperties(common, tree, integer(tree.get("subdivisions")));
            case "sequence" -> {
                if (!(tree.get("sequence") instanceof String sequence)) throw new IllegalArgumentException(who + " names no sequence");
                yield new CgVfxSequenceRendererProperties(common, tree, sequence,
                        tree.get("play_rate") instanceof Number n ? n.floatValue() : 1f);
            }
            case "system" -> throw new IllegalArgumentException(who + ": a system renderer places analytic emitters, which are not built yet");
            case "anim_trail" -> animTrail(who, common, tree);
            case "beam" -> beam(who, common, tree);
            case "light" -> new CgVfxLightRendererProperties(common, tree, !Boolean.FALSE.equals(tree.get("inverse_squared_falloff")),
                    tree.get("intensity_scale") instanceof Number n ? n.floatValue() : 1f, tree.get("spawn_fraction") instanceof Number n ? n.floatValue() : 1f,
                    Boolean.TRUE.equals(tree.get("affects_translucency")),
                    tree.get("volumetric_scattering") instanceof Number n ? n.floatValue() : 0f,
                    Boolean.TRUE.equals(tree.get("allow_mega_lights")));
            default -> throw new IllegalArgumentException(who + " has no kind '" + kind + "'");
        };
        built(who, r);
        if (common.definition() != null) common.definition().requireUsage(usage(r), who);
        return r;
    }

    /**
     * The usage a renderer draws its material as: sprites and arcs {@code sprites}, meshes and clips' parts {@code
     * mesh_particles}, ribbons, trails and beams {@code ribbons} (Unreal's beam-and-trail usage).
     */
    public static CgMaterialUsage usage(CgVfxRendererProperties r) {
        if (r instanceof CgVfxMeshRendererProperties || r instanceof CgVfxSequenceRendererProperties) {
            return CgMaterialUsage.MESH_PARTICLES;
        }
        if (r instanceof CgVfxRibbonRendererProperties || r instanceof CgVfxBeamRendererProperties) {
            return CgMaterialUsage.RIBBONS;
        }
        return CgMaterialUsage.SPRITES;
    }

    private static CgVfxRibbonRendererProperties ribbon(String who, Common common, Map<String, Object> tree) {
        Map<String, Object> t = fields(tree.get("tessellation"));
        CgVfxRibbonRendererProperties.Tessellation d = CgVfxRibbonRendererProperties.Tessellation.DEFAULT;
        float angle = t.get("angle") instanceof Number n ? n.floatValue() : 15f;
        // InitializeTessellation: an angle under a degree is a degree.
        if (angle > 0f && angle < 1f) angle = 1f;
        CgVfxRibbonRendererProperties.Tessellation tessellation = new CgVfxRibbonRendererProperties.Tessellation(
                choice(who, t, "mode", CgVfxRibbonRendererProperties.TessellationMode.class, "automatic"),
                t.get("factor") instanceof Number n ? n.intValue() : d.factor(), (float) Math.toRadians(angle),
                d.constant(), d.screenSpace(), t.get("tension") instanceof Number n ? n.floatValue() : 0f);
        int widthSegments = tree.get("width_segments") instanceof Number n ? n.intValue() : 1;
        if (widthSegments < 1) throw new IllegalArgumentException(who + " has " + widthSegments + " width segments");
        return new CgVfxRibbonRendererProperties(common, tree,
                choice(who, tree, "facing", CgVfxRibbonRendererProperties.Facing.class, "screen"),
                choice(who, tree, "shape", CgVfxRibbonRendererProperties.Shape.class, "plane"),
                widthSegments, integer(tree.get("max_ribbons")), uv(who, fields(tree.get("uv0"))), uv(who, fields(tree.get("uv1"))),
                tessellation, Boolean.TRUE.equals(tree.get("link_order_by_id")), integer(tree.get("max_link_gap")),
                tree.get("trail") instanceof Map<?, ?> trail ? trail(who, copy(trail)) : null, null);
    }

    // UParticleModuleTypeDataAnimTrail's defaults (ParticleTrailModules.cpp:243-251), its 10 Unreal units in blocks.
    private static CgVfxRibbonRendererProperties animTrail(String who, Common common, Map<String, Object> tree) {
        if (!(tree.get("edge") instanceof List<?> edge) || edge.size() != 2) throw new IllegalArgumentException(who + " names no edge of two points");
        String[] points = new String[2];
        for (int i = 0; i < 2; i++) {
            if (!(edge.get(i) instanceof String s) || !s.startsWith("user.")) {
                throw new IllegalArgumentException(who + ".edge " + edge.get(i) + " is no user parameter");
            }
            points[i] = s.substring("user.".length());
        }
        Map<String, Object> t = fields(tree.get("tessellation")), tangents = fields(tree.get("tangents"));
        float angle = t.get("angle") instanceof Number n ? n.floatValue() : 0f;
        int retract = 0;
        float fadeAlpha = 0f, fadeWidth = 0f;
        Object stop = tree.get("stop");
        if (stop instanceof Map<?, ?> s) {
            retract = s.get("retract") instanceof Number n ? Math.round(n.floatValue()) : 0;
            Map<String, Object> fade = fields(s.get("fade"));
            fadeAlpha = fade.get("alpha") instanceof Number n ? n.floatValue() : 0f;
            fadeWidth = fade.get("width") instanceof Number n ? n.floatValue() : 0f;
        } else if (stop != null && !"dead_on_deactivate".equals(stop)) {
            throw new IllegalArgumentException(who + ".stop " + stop + ": dead_on_deactivate or a retract");
        }
        CgVfxRibbonRendererProperties.AnimTrail anim = new CgVfxRibbonRendererProperties.AnimTrail(points[0], points[1],
                choice(who, tree, "width_mode", CgVfxRibbonRendererProperties.WidthMode.class, "centre"),
                integer(tree.get("life")), t.get("distance") instanceof Number n ? n.floatValue() : 0.1f,
                (angle % 180f) / 180f, t.get("width") instanceof Number n ? n.floatValue() : 0f, integer(t.get("max")),
                !Boolean.FALSE.equals(tangents.get("recalculate_previous")), Boolean.TRUE.equals(tangents.get("every_frame")),
                along(who, "width", tree.get("width"), 1, new float[] {1f}),
                along(who, "colour", tree.get("colour"), 4, new float[] {1f, 1f, 1f, 1f}),
                tree.get("tiling_distance") instanceof Number n ? n.floatValue() : 0f, retract, fadeAlpha, fadeWidth);
        return new CgVfxRibbonRendererProperties(common, tree, CgVfxRibbonRendererProperties.Facing.SCREEN,
                CgVfxRibbonRendererProperties.Shape.PLANE, 1, 0, uv(who, fields(tree.get("uv0"))),
                CgVfxRibbonRendererProperties.Uv.DEFAULT, CgVfxRibbonRendererProperties.Tessellation.DEFAULT, false, 0, null, anim);
    }

    /** An anim trail's width or colour: a constant, or keys {@code by: length}, newest 0 to oldest 1. */
    private static CgVfxRibbonRendererProperties.Along along(String who, String field, @Nullable Object value, int components,
                                                             float[] fallback) {
        if (value == null) return CgVfxRibbonRendererProperties.Along.constant(fallback);
        if (value instanceof Number n) return CgVfxRibbonRendererProperties.Along.constant(n.floatValue());
        if (value instanceof List<?>) return CgVfxRibbonRendererProperties.Along.constant(channels(who, field, value, components));
        if (!(value instanceof Map<?, ?> m) || !"length".equals(m.get("by"))) {
            throw new IllegalArgumentException(who + "." + field + " " + value + ": a value or keys by length");
        }
        Map<String, Object> form = fields(m.get(components == 1 ? "curve" : "gradient"));
        if (!(form.get("keys") instanceof List<?> keys) || keys.isEmpty()) throw new IllegalArgumentException(who + "." + field + " has no keys");
        if (keys.size() > CgVfxRibbonRendererProperties.Along.MAX_KEYS) {
            throw new IllegalArgumentException(who + "." + field + " has " + keys.size() + " keys; an anim trail holds "
                    + CgVfxRibbonRendererProperties.Along.MAX_KEYS);
        }
        float[] times = new float[keys.size()], values = new float[keys.size() * components];
        for (int i = 0; i < keys.size(); i++) {
            if (!(keys.get(i) instanceof List<?> key) || key.size() != 2 || !(key.get(0) instanceof Number at)) {
                throw new IllegalArgumentException(who + "." + field + " key " + keys.get(i) + " is no [time, value]");
            }
            times[i] = at.floatValue();
            float[] v = components == 1 && key.get(1) instanceof Number n ? new float[] {n.floatValue()} : channels(who, field, key.get(1), components);
            System.arraycopy(v, 0, values, i * components, components);
        }
        return new CgVfxRibbonRendererProperties.Along(times, values, components);
    }

    /** A colour of three or four channels, alpha 1 where it has three. */
    private static float[] channels(String who, String field, Object value, int components) {
        if (!(value instanceof List<?> list) || list.size() < Math.min(components, 3) || list.size() > components) {
            throw new IllegalArgumentException(who + "." + field + " " + value + " is no " + components + "-channel value");
        }
        float[] out = new float[components];
        Arrays.fill(out, 1f);
        for (int i = 0; i < list.size(); i++) out[i] = ((Number) list.get(i)).floatValue();
        return out;
    }

    /** A struct field's entries, none where it is absent. */
    private static Map<String, Object> fields(@Nullable Object value) {
        return value instanceof Map<?, ?> m ? copy(m) : Map.of();
    }

    private static CgVfxRibbonRendererProperties.Uv uv(String who, Map<String, Object> u) {
        CgVfxRibbonRendererProperties.Uv d = CgVfxRibbonRendererProperties.Uv.DEFAULT;
        float[] offset = u.get("offset") != null ? vector(who, "offset", u.get("offset"), 2) : new float[] {d.offsetU(), d.offsetV()};
        float[] scale = u.get("scale") != null ? vector(who, "scale", u.get("scale"), 2) : new float[] {d.scaleU(), d.scaleV()};
        return new CgVfxRibbonRendererProperties.Uv(
                choice(who, u, "distribution", CgVfxRibbonRendererProperties.Distribution.class, "scaled_using_ribbon_segment_length"),
                choice(who, u, "leading_edge", CgVfxRibbonRendererProperties.Edge.class, "locked"),
                choice(who, u, "trailing_edge", CgVfxRibbonRendererProperties.Edge.class, "locked"),
                u.get("tiling_length") instanceof Number n ? n.floatValue() : d.tilingLength(), offset[0], offset[1], scale[0], scale[1]);
    }

    // UParticleModuleTypeDataRibbon's defaults, its 15 Unreal units of distance step in blocks.
    private static CgVfxRibbonRendererProperties.Trail trail(String who, Map<String, Object> t) {
        Map<String, Object> tess = fields(t.get("tessellation")), tangents = fields(t.get("tangents"));
        return new CgVfxRibbonRendererProperties.Trail(
                t.get("max_trails") instanceof Number n ? n.intValue() : 1,
                t.get("max_particles") instanceof Number n ? n.intValue() : 500,
                t.get("sheets") instanceof Number n ? n.intValue() : 1,
                choice(who, t, "render_axis", CgVfxRibbonRendererProperties.RenderAxis.class, "camera_up"),
                tess.get("distance") instanceof Number n ? n.floatValue() : 0.15f,
                tess.get("max") instanceof Number n ? n.intValue() : 25,
                tess.get("tangent_scale") instanceof Number n ? n.floatValue() : 5f,
                Boolean.TRUE.equals(tess.get("tangent_interp")),
                !Boolean.FALSE.equals(tangents.get("recalculate_previous")), Boolean.TRUE.equals(tangents.get("every_frame")),
                t.get("tangent_spawn_scale") instanceof Number n ? n.floatValue() : 0f,
                Boolean.TRUE.equals(t.get("spawn_initial_particle")), !Boolean.FALSE.equals(t.get("clip_source_segment")),
                !Boolean.FALSE.equals(t.get("dead_on_deactivate")), !Boolean.FALSE.equals(t.get("dead_on_source_loss")),
                t.get("tiling_distance") instanceof Number n ? n.floatValue() : 0f);
    }

    /** Fails naming {@code who}'s first field the engine does not draw yet, rather than play it as something else. */
    private static void built(String who, CgVfxRendererProperties r) {
        String unbuilt = null;
        if (r instanceof CgVfxSpriteRendererProperties s) {
            s.factoryFacing(who);
            if (s.sort() == CgVfxSpriteRendererProperties.Sort.SPAWN_ORDER) unbuilt = "sort " + s.sort();
            else if (s.subImage() != null && s.subImage().isTable()) unbuilt = "a rect-table sub_image";
            else if (s.cameraDistance() != null) unbuilt = "camera_distance";
            else if (s.castShadows()) unbuilt = "cast_shadows";
            else if (!Float.isNaN(s.softDepth())) unbuilt = "soft_depth";
        } else if (r instanceof CgVfxMeshRendererProperties m) {
            if (m.prefab() != null || m.meshBinding() != null || m.meshes().size() > 1) unbuilt = "a prefab, a bound mesh or several meshes";
            else if (m.sort() == CgVfxSpriteRendererProperties.Sort.SPAWN_ORDER) unbuilt = "sort " + m.sort();
            else if (!m.meshes().isEmpty() && (m.meshes().get(0).get("pivot_offset") != null
                    || m.meshes().get(0).get("pivot_space") != null)) unbuilt = "a mesh's pivot_offset";
            else if (m.subImage() != null && m.subImage().isTable()) unbuilt = "a rect-table sub_image";
            else if (m.cameraDistance() != null) unbuilt = "camera_distance";
            else if (m.castShadows()) unbuilt = "cast_shadows";
        } else if (r instanceof CgVfxRibbonRendererProperties b) {
            // Custom facing reads a facing particles carry no attribute for; a custom shape has no vertices to read.
            if (b.facing() != CgVfxRibbonRendererProperties.Facing.SCREEN) unbuilt = "facing " + b.facing();
            else if (b.shape() == CgVfxRibbonRendererProperties.Shape.CUSTOM) unbuilt = "shape custom";
            else if (b.maxRibbons() != 0) unbuilt = "max_ribbons";
        } else if (r instanceof CgVfxArcRendererProperties a) {
            if (a.subdivisions() != 0) unbuilt = "subdivisions";
        }
        if (unbuilt != null) throw new IllegalArgumentException(who + " sets " + unbuilt + ", which is not built yet");
    }

    private static Common common(String who, Map<String, Object> tree, @Nullable CgAssetCompiler.Dependencies deps) {
        if (!(tree.get("id") instanceof String id)) throw new IllegalArgumentException(who + " has no id");
        CgQuality quality = tree.get("quality") instanceof String q ? CgQuality.valueOf(q.toUpperCase(Locale.ROOT)) : null;
        String material = null;
        CgMaterialDefinition definition = null;
        Object m = tree.get("material");
        if (m instanceof String s) {
            material = s;
            if (deps != null) {
                Object d = deps.definition(CgLookKinds.MATERIAL, s);
                // Null: the caller holds no compiled hard references; a pipeline answers every one or fails first.
                if (d instanceof CgMaterialDefinition md) definition = md;
                else if (d != null) throw new IllegalArgumentException(who + "'s material " + s + " compiled to " + d.getClass().getSimpleName() + ", not a material: is CgMaterialDefinitions registered?");
            }
        } else if (m instanceof Map<?, ?>) {
            throw new IllegalArgumentException(who + " takes its material from a user parameter, which is not built yet:"
                    + " name a material asset");
        } else if (m != null) {
            throw new IllegalArgumentException(who + "'s material is an asset, not " + m);
        }
        Map<String, Object> bind = stringKeyed(tree.get("bind"));
        for (Map.Entry<String, Object> b : bind.entrySet()) {
            Object source = b.getValue() instanceof Map<?, ?> form ? form.get("bind") : b.getValue();
            String from = String.valueOf(source);
            if (from.startsWith("particles.")) {
                throw new IllegalArgumentException(who + " binds " + b.getKey() + " to " + from + ": a particle's own"
                        + " attributes are read in its material (cg_dynamic_parameter, CG_PARTICLE_COLOR), not bound");
            }
            if (!from.startsWith("user.")) {
                throw new IllegalArgumentException(who + " binds " + b.getKey() + " to " + from + ": a bind reads a user"
                        + " parameter, user.<name>");
            }
        }
        List<String> keywords = new ArrayList<>();
        if (tree.get("keywords") instanceof List<?> list) for (Object k : list) keywords.add(String.valueOf(k));
        return new Common(id, !Boolean.FALSE.equals(tree.get("enabled")), quality, integer(tree.get("sort_order_hint")),
                Boolean.TRUE.equals(tree.get("motion_vectors")), material, definition, bind,
                stringKeyed(tree.get("values")), Collections.unmodifiableList(keywords),
                "first".equals(tree.get("dynamic_parameter_index")) ? CgVfxDynamicParameterIndex.FIRST : CgVfxDynamicParameterIndex.BY_INDEX,
                CgVfxMacroUv.read(who, tree.get("macro_uv"), deps == null ? CgUnits.HOST : deps.units(), CgVfxMacroUv.DEFAULT));
    }

    /**
     * A beam renderer: Cascade's TypeDataBeam2 fields; what is not built fails by name. Its {@code distance} is read only
     * by Cascade's distance method, which a beam with a target never takes.
     */
    private static CgVfxBeamRendererProperties beam(String who, Common common, Map<String, Object> tree) {
        String unbuilt = null;
        if (!"target".equals(String.valueOf(tree.getOrDefault("beam_method", "target")))) unbuilt = "beam_method " + tree.get("beam_method");
        else if (!"none".equals(String.valueOf(tree.getOrDefault("taper_method", "none")))) unbuilt = "taper_method " + tree.get("taper_method");
        else if (Boolean.FALSE.equals(tree.get("render_geometry"))) unbuilt = "render_geometry false";
        else if (Boolean.TRUE.equals(tree.get("render_direct_line")) || Boolean.TRUE.equals(tree.get("render_lines"))
                || Boolean.TRUE.equals(tree.get("render_tessellation"))) unbuilt = "its editor lines";
        if (unbuilt != null) throw new IllegalArgumentException(who + " sets " + unbuilt + ", which is not built yet");
        int sheets = tree.get("sheets") instanceof Number n ? n.intValue() : 1;
        int points = integer(tree.get("interpolation_points"));
        if (sheets < 1) throw new IllegalArgumentException(who + " has " + sheets + " sheets");
        if (points < 0 || points > CgVfxBeamRendererProperties.MAX_INTERPOLATION_POINTS) {
            throw new IllegalArgumentException(who + " has " + points + " interpolation points; 0 to "
                    + CgVfxBeamRendererProperties.MAX_INTERPOLATION_POINTS);
        }
        int count = tree.get("max_beam_count") instanceof Number n ? n.intValue() : 0;
        return new CgVfxBeamRendererProperties(common, tree, sheets, count < 1 ? 1 : count,
                tree.get("speed") instanceof Number n ? n.floatValue() : 0f, points, integer(tree.get("up_vector_step_size")),
                tree.get("texture_tile") instanceof Number n ? n.floatValue() : 1f,
                tree.get("texture_tile_distance") instanceof Number n ? n.floatValue() : 0f,
                Boolean.TRUE.equals(tree.get("always_on")));
    }

    private static CgVfxMeshRendererProperties mesh(String who, Common common, Map<String, Object> tree,
                                                    @Nullable CgAssetCompiler.Dependencies deps) {
        List<Map<String, Object>> meshes = new ArrayList<>();
        String prefab = null, binding = null;
        Object m = tree.get("meshes");
        if (m instanceof List<?> list) {
            for (Object e : list) meshes.add(copy((Map<?, ?>) e));
        } else if (m instanceof Map<?, ?> one && one.get("prefab") instanceof String p) {
            prefab = p;
        } else if (m instanceof Map<?, ?> one && one.get("bind") instanceof String b) {
            binding = b;
        } else if (m != null) {
            throw new IllegalArgumentException(who + "'s meshes are a list, { prefab } or { bind }, not " + m);
        }
        List<String> overrides = new ArrayList<>();
        List<CgMaterialDefinition> definitions = new ArrayList<>();
        if (tree.get("material_overrides") instanceof List<?> list) {
            if (list.size() > CgVfxMeshRendererProperties.MAX_OVERRIDES) {
                throw new IllegalArgumentException(who + " overrides " + list.size() + " sections' materials; at most "
                        + CgVfxMeshRendererProperties.MAX_OVERRIDES);
            }
            for (Object o : list) {
                String id = String.valueOf(o);
                overrides.add(id);
                Object d = deps == null ? null : deps.definition(CgLookKinds.MATERIAL, id);
                if (d != null && !(d instanceof CgMaterialDefinition)) {
                    throw new IllegalArgumentException(who + "'s material override " + id + " compiled to " + d.getClass().getSimpleName() + ", not a material");
                }
                definitions.add((CgMaterialDefinition) d);
            }
        }
        return new CgVfxMeshRendererProperties(common, tree, Collections.unmodifiableList(meshes), prefab, binding,
                Collections.unmodifiableList(overrides), Collections.unmodifiableList(definitions),
                choice(who, tree, "facing", CgVfxMeshRendererProperties.Facing.class, "default"), map(tree.get("locked_axis")),
                tree.get("facing_up") == null ? null : choice(who, tree, "facing_up", CgVfxMeshRendererProperties.FacingUp.class, null),
                tree.get("roll_about") == null ? null : choice(who, tree, "roll_about", CgVfxMeshRendererProperties.RollAbout.class, null),
                tree.get("pre_rotation"),
                sort(who, tree), flipbook(who, tree.get("sub_image")), floats(tree.get("camera_distance"), 2),
                Boolean.TRUE.equals(tree.get("cast_shadows")));
    }

    private static CgVfxSpriteRendererProperties.Sort sort(String who, Map<String, Object> tree) {
        return choice(who, tree, "sort", CgVfxSpriteRendererProperties.Sort.class, "none");
    }

    /** A renderer's {@code sub_image} as its flipbook; null for none. */
    @Nullable
    static CgVfxFlipbook flipbook(String who, @Nullable Object subImage) {
        if (!(subImage instanceof Map<?, ?> s)) return null;
        CgVfxFlipbook f;
        if (s.get("rects") instanceof List<?> rects) {
            float[] all = new float[rects.size() * 4];
            for (int i = 0; i < rects.size(); i++) System.arraycopy(floats(rects.get(i), 4), 0, all, i * 4, 4);
            f = CgVfxFlipbook.rects(all);
        } else {
            float[] size = floats(s.get("size"), 2);
            if (size == null) throw new IllegalArgumentException(who + "'s sub_image has neither size nor rects");
            f = CgVfxFlipbook.grid((int) size[0], (int) size[1]);
        }
        // Its frames alone: its emitter's sub_uv_animation plays them (CgVfxSubUvAnimation), frame 0 without one.
        return f.direct().blend(Boolean.TRUE.equals(s.get("blend")));
    }

    private static <E extends Enum<E>> E choice(String who, Map<String, Object> tree, String key, Class<E> type, String fallback) {
        String value = tree.get(key) instanceof String s ? s : fallback;
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(who + " has no " + key + " '" + value + "'");
        }
    }

    @Nullable
    private static float[] floats(@Nullable Object value, int n) {
        if (!(value instanceof List<?> list)) return null;
        float[] out = new float[n];
        for (int i = 0; i < Math.min(n, list.size()); i++) out[i] = ((Number) list.get(i)).floatValue();
        return out;
    }

    /** {@code value}, a field of {@code n} numbers: refused by name in any other form. */
    private static float[] vector(String who, String field, Object value, int n) {
        if (!(value instanceof List<?> list) || list.size() != n || !list.stream().allMatch(Number.class::isInstance)) {
            throw new IllegalArgumentException(who + "'s " + field + " is " + value + ", not " + n + " numbers");
        }
        return floats(value, n);
    }

    private static int integer(@Nullable Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    @Nullable
    private static Map<String, Object> map(@Nullable Object value) {
        return value instanceof Map<?, ?> m ? copy(m) : null;
    }

    private static Map<String, Object> stringKeyed(@Nullable Object value) {
        return value instanceof Map<?, ?> m ? Collections.unmodifiableMap(copy(m)) : Map.of();
    }

    private static Map<String, Object> copy(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    // ── Java ─────────────────────────────────────────────────────────────────────────────

    /** A sprite renderer of id {@code id}. */
    public static Builder sprite(String id) {
        return new Builder(id, "sprite");
    }

    /** A mesh renderer of id {@code id}. */
    public static Builder mesh(String id) {
        return new Builder(id, "mesh");
    }

    /** A ribbon renderer of id {@code id}. */
    public static Builder ribbon(String id) {
        return new Builder(id, "ribbon");
    }

    /** An arc renderer of id {@code id}. */
    public static Builder arc(String id) {
        return new Builder(id, "arc");
    }

    /** A beam renderer of id {@code id}: its particles need a beam_source and a beam_target. */
    public static Builder beam(String id) {
        return new Builder(id, "beam");
    }

    /** A light renderer of id {@code id}: its particles need a light_attributes module. */
    public static Builder light(String id) {
        return new Builder(id, "light");
    }

    /**
     * An anim trail renderer of id {@code id}: a strip swept by the edge between user parameters {@code first} and
     * {@code second}, points from its effect's origin, which the effect sets each tick.
     */
    public static Builder animTrail(String id, String first, String second) {
        return new Builder(id, "anim_trail").field("edge", List.of("user." + first, "user." + second));
    }

    /** A sequence renderer of id {@code id}, each particle playing {@code sequence}. */
    public static Builder sequence(String id, String sequence) {
        return new Builder(id, "sequence").field("sequence", sequence);
    }

    /**
     * Writes a {@code renderers:} entry as a file would hold it, then compiles it: a renderer built in Java is the
     * renderer its file loads.
     *
     * <ul>
     *   <li>{@link #field} sets any of the kind's fields by its file key; the named setters are the common ones.</li>
     *   <li>A key the kind does not declare is refused when the emitter's asset validates.</li>
     * </ul>
     */
    public static final class Builder {

        private final Map<String, Object> tree = new LinkedHashMap<>();
        private final Map<String, Object> bind = new LinkedHashMap<>(), values = new LinkedHashMap<>();
        private final List<String> keywords = new ArrayList<>();
        private CgMaterialDefinition definition;
        private final List<CgMaterialDefinition> overrides = new ArrayList<>();

        private Builder(String id, String kind) {
            tree.put("id", id);
            tree.put("kind", kind);
        }

        /** Its material asset. */
        public Builder material(String material) {
            tree.put("material", material);
            return this;
        }

        /** Its material, compiled: its id is written, and {@link Common#definition()} is it. */
        public Builder material(CgMaterialDefinition material) {
            definition = material;
            return material(material.id());
        }

        /**
         * A mesh renderer's materials a section, in place of its material on the sections they reach: compiled, so
         * their ids are written and each is what its section draws with.
         *
         * <pre>{@code
         * CgVfxRenderers.mesh("blade").material(STEEL).field("meshes", List.of(Map.of("mesh", "mymod:models/sword.glb")))
         *         .materialOverrides(STEEL, GLOW)   // section 0 steel, section 1 glowing, any later section STEEL
         *         .build();
         * }</pre>
         */
        public Builder materialOverrides(CgMaterialDefinition... materials) {
            overrides.clear();
            List<String> ids = new ArrayList<>();
            for (CgMaterialDefinition m : materials) {
                overrides.add(m);
                ids.add(m.id());
            }
            tree.put("material_overrides", ids);
            return this;
        }

        /** Drawn from {@code quality} up. */
        public Builder quality(CgQuality quality) {
            tree.put("quality", quality.name().toLowerCase(Locale.ROOT));
            return this;
        }

        /** Its place among its emitter's renderers. */
        public Builder sortOrderHint(int hint) {
            tree.put("sort_order_hint", hint);
            return this;
        }

        /** Its own particle macro UVs over its system's: Cascade's required module with its override on. */
        public Builder macroUv(CgVfxMacroUv macroUv) {
            tree.put("macro_uv", macroUv.tree());
            return this;
        }

        /** Which dynamic parameter each index of {@code cg_dynamic_parameter} reads: {@code FIRST} for a Cascade emitter's. */
        public Builder dynamicParameterIndex(CgVfxDynamicParameterIndex index) {
            if (index == CgVfxDynamicParameterIndex.BY_INDEX) tree.remove("dynamic_parameter_index");
            else tree.put("dynamic_parameter_index", index.name().toLowerCase(Locale.ROOT));
            return this;
        }

        /** Not drawn. */
        public Builder disabled() {
            tree.put("enabled", false);
            return this;
        }

        /** Material parameter {@code parameter} read per draw from user parameter {@code source}, {@code user.<name>}. */
        public Builder bind(String parameter, String source) {
            bind.put(parameter, source);
            return this;
        }

        /** Material parameter {@code parameter} read per draw from {@code param}, bound by its full name. */
        public Builder bind(String parameter, CgVfxParam param) {
            return bind(parameter, "user." + CgVfxLayout.fullName(param));
        }

        /** Material parameter {@code parameter} read per draw from {@code param} times {@code scale}. */
        public Builder bind(String parameter, CgVfxParam param, float scale) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put("bind", "user." + CgVfxLayout.fullName(param));
            form.put("scale", (double) scale);
            bind.put(parameter, form);
            return this;
        }

        /** Material parameter {@code parameter} held at {@code value} on this renderer alone. */
        public Builder value(String parameter, Object value) {
            values.put(parameter, value);
            return this;
        }

        /** A material keyword it enables. */
        public Builder keyword(String keyword) {
            keywords.add(keyword);
            return this;
        }

        /** A sprite's alignment: {@code unaligned}, {@code velocity} or {@code custom}. */
        public Builder alignment(String alignment) {
            return field("alignment", alignment);
        }

        /** A sprite's or mesh's facing, by its file name. */
        public Builder facing(String facing) {
            return field("facing", facing);
        }

        /** A sprite's or mesh's flipbook, its {@code sub_image} map. */
        public Builder subImage(Map<String, Object> subImage) {
            return field("sub_image", subImage);
        }

        /** Any field of its kind, by its file key. */
        public Builder field(String key, Object value) {
            tree.put(key, value);
            return this;
        }

        /** The renderer, compiled from the entry written. */
        public CgVfxRendererProperties build() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", tree.get("id"));
            entry.put("kind", tree.get("kind"));
            for (String key : COMMON_KEYS) {
                switch (key) {
                    case "bind" -> { if (!bind.isEmpty()) entry.put("bind", new LinkedHashMap<>(bind)); }
                    case "values" -> { if (!values.isEmpty()) entry.put("values", new LinkedHashMap<>(values)); }
                    case "keywords" -> { if (!keywords.isEmpty()) entry.put("keywords", new ArrayList<>(keywords)); }
                    default -> { if (tree.containsKey(key)) entry.put(key, tree.get(key)); }
                }
            }
            for (Map.Entry<String, Object> e : tree.entrySet()) entry.putIfAbsent(e.getKey(), e.getValue());
            CgMaterialDefinition d = definition;
            List<CgMaterialDefinition> more = List.copyOf(overrides);
            if (d == null && more.isEmpty()) return compile("java", entry, null);
            return compile("java", entry, (kind, id) -> {
                if (d != null && id.equals(d.id())) return d;
                for (CgMaterialDefinition o : more) if (id.equals(o.id())) return o;
                return null;
            });
        }
    }
}
