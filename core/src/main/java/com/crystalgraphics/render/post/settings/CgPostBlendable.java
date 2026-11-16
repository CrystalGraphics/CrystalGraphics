package com.crystalgraphics.render.post.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;

/**
 * One entry of a post block's {@code materials}, compiled: Unreal's weighted blendable, a post material at a weight,
 * made a dynamic instance by its {@code values} and {@code textures}. A sequence's {@code post} spawnable and a
 * prefab's {@code post} node make them blendables of their volume's settings.
 *
 * <pre>{@code
 * # a post block
 * materials:
 *   - { name: overlay, material: kakarot_kit:Art/BG/Area02/PostProcess/Area02_PostProcess,
 *       values: { DummySunVector: [0.814867, -0.468214, 0.341713, 0] } }
 * }</pre>
 *
 * <pre>{@code
 * CgPostBlendable b = CgPostBlendable.of(entry, "mymod:arena: post.materials[0]", deps, null);
 * CgMaterialInstance drawn = b.addTo(settings, priority);   // a blendable of settings, at its material's post.stage
 * scene.postVolume(settings).spawn();
 * }</pre>
 *
 * <ul>
 *   <li>{@code values} and {@code textures} make a child of the material ({@link CgMaterialDefinitions#derive}), so a
 *       key the material does not declare fails at load, naming it.</li>
 *   <li>{@code material} is null where materials have no compiler (a dedicated server): it draws nothing there.</li>
 * </ul>
 *
 * @param name          what a {@code material} track's binding names: its {@code name}, else its material's id
 * @param material      the material compiled, its values and textures over it; null where materials have no compiler
 * @param screen        what {@code screen_point} follows, or null for none
 * @param screenVector  the vector parameter whose xy take the point, or null
 * @param screenX       the scalar taking x, or null
 * @param screenY       the scalar taking y, or null
 * @param entry         the entry as its file wrote it: what a prefab writes back
 */
public record CgPostBlendable(String name, @Nullable CgMaterialDefinition material, float weight, boolean enabled,
                              @Nullable CgSequenceBinding screen, @Nullable String screenVector, @Nullable String screenX,
                              @Nullable String screenY, boolean screenClamp, Map<?, ?> entry) {

    /**
     * {@code entry} compiled, its material from {@code deps}. {@code bind} resolves {@code screen_point.target}; null
     * where nothing can be followed (a stage), which refuses a {@code screen_point}.
     *
     * @param at the entry's path, the asset's id first, for each failure
     */
    public static CgPostBlendable of(Map<?, ?> entry, String at, CgAssetCompiler.Dependencies deps,
                                     @Nullable Function<Object, CgSequenceBinding> bind) {
        String id = (String) entry.get("material");
        Object d = deps.definition(CgLookKinds.MATERIAL, id);
        if (d == null) throw new CgAssetException(at + ".material: no material " + id);
        // Null where materials have no compiler (a dedicated server, a headless gate): it draws nothing there
        CgMaterialDefinition material = d instanceof CgMaterialDefinition m ? m : null;
        if (material != null && (entry.containsKey("values") || entry.containsKey("textures"))) {
            Map<String, Object> overrides = new LinkedHashMap<>();
            if (entry.get("values") != null) overrides.put("values", entry.get("values"));
            if (entry.get("textures") != null) overrides.put("textures", entry.get("textures"));
            try {
                material = CgMaterialDefinitions.derive(material, overrides);
            } catch (CgAssetException e) {
                throw new CgAssetException(at + ": " + e.getMessage());
            }
        }
        Map<?, ?> screen = entry.get("screen_point") instanceof Map<?, ?> s && !Boolean.FALSE.equals(s.get("enabled")) ? s : null;
        if (screen != null && bind == null) throw new CgAssetException(at + ".screen_point: there is nothing here to follow");
        return new CgPostBlendable(entry.get("name") instanceof String n ? n : id, material,
                entry.get("weight") instanceof Number w ? w.floatValue() : 1f, !Boolean.FALSE.equals(entry.get("enabled")),
                screen == null ? null : bind.apply(screen.get("target")), screen == null ? null : (String) screen.get("vector"),
                screen == null ? null : (String) screen.get("x"), screen == null ? null : (String) screen.get("y"),
                screen != null && Boolean.TRUE.equals(screen.get("clamp")), entry);
    }

    /**
     * An instance of its material added to {@code settings} as a blendable at its weight (0 where disabled), at its
     * material's point, {@code priority} among that point's: its volume's. Null where no material compiled.
     */
    @Nullable
    public CgMaterialInstance addTo(CgPostSettings settings, int priority) {
        if (material == null) return null;
        CgMaterialInstance instance = CgMaterial.of(material).instance();
        settings.blendable(instance, point(), enabled ? weight : 0f, priority);
        return instance;
    }

    /**
     * Where its material's {@code post.stage} draws (after 4.27 PostProcessing.cpp's chains): before translucency onto
     * the lit opaque scene ({@code SURFACES}), before tonemapping after the transparent passes ({@code SCENE}), the rest
     * over the display picture. Unreal's default is after tonemapping; {@code replacing_tonemapper} draws over the display
     * picture until the composite takes one.
     */
    public CgPostPoint point() {
        Object stage = material == null || material.post() == null ? null : material.post().get("stage");
        if ("before_translucency".equals(stage)) return CgPostPoint.SURFACES;
        if ("before_tonemapping".equals(stage)) return CgPostPoint.SCENE;
        return CgPostPoint.DISPLAY;
    }
}
