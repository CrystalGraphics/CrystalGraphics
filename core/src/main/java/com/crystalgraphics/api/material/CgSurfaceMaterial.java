package com.crystalgraphics.api.material;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * The engine's base surface material, instanced with a texture and Unreal's base properties: a blend mode, a shading
 * model, two-sided, an emissive scale. What a mod drawing a model with no shader of its own builds; a Minecraft render
 * type is these properties, never a name in the engine's API.
 *
 * <pre>{@code
 * static final CgMaterial SKIN = CgSurfaceMaterial.of("mymod:dragon_skin", "mymod:textures/entity/dragon.png").build();
 * static final CgMaterial CLOTH = CgSurfaceMaterial.of("mymod:dragon_cloth", "mymod:textures/entity/cloth.png")
 *         .blend(CgBlendMode.ALPHA).twoSided().build();
 * static final CgMaterial EYES = CgSurfaceMaterial.of("mymod:dragon_eyes", "mymod:textures/entity/eyes.png")
 *         .shading(CgShadingModels.UNLIT).emissive(2f).build();
 * static final CgMaterial GOLD = CgSurfaceMaterial.of("mymod:gold").tint(0xFFFFC030).build();   // a colour, no texture
 * }</pre>
 *
 * <p>Minecraft's render types, as properties:</p>
 * <pre>{@code
 * entitySolid                      .of(id, texture)                                   // opaque, lit by the world
 * entityCutout, entityCutoutNoCull .masked(0.1f), and .twoSided() for no-cull
 * entityTranslucent                .blend(CgBlendMode.ALPHA)
 * eyes                             .blend(CgBlendMode.ADDITIVE).shading(CgShadingModels.UNLIT)
 * entityDecal                      CgSurfaceMaterial.decal(id, texture)               // on a CgDecalComponent
 * }</pre>
 *
 * <ul>
 *   <li>Its texture is sampled nearest and clamped, as an entity's is; {@link Builder#sampling} for a model's own.</li>
 *   <li>Under {@code UNLIT} the emissive is the colour drawn, at 1 unless {@link Builder#emissive} says otherwise; lit,
 *       it is a glow over the lit colour, 0 unless set.</li>
 *   <li>The colour is the texture times the vertex colour and {@link Builder#tint}; its alpha is the opacity a blend
 *       mode, or the cut {@link Builder#masked} reads.</li>
 *   <li>Each blend family and shading base is a shader of its own under {@code materials/surface/}, since a material
 *       names only the outputs its blend mode and shading model draw.</li>
 * </ul>
 */
public final class CgSurfaceMaterial {

    private static final String SHADERS = "crystalgraphics:shaders/materials/surface/";

    private CgSurfaceMaterial() {
    }

    /** A surface of its {@link Builder#tint} alone, white until tinted: opaque and lit by the world until told otherwise. */
    public static Builder of(String id) {
        return new Builder(id, false, null, null);
    }

    /** A surface drawing {@code texture}, a texture asset id: opaque and lit by the world until told otherwise. */
    public static Builder of(String id, String texture) {
        return new Builder(id, false, Objects.requireNonNull(texture, "texture"), null);
    }

    /** A surface drawing {@code texture}, made in code. */
    public static Builder of(String id, CgTexture texture) {
        return new Builder(id, false, null, Objects.requireNonNull(texture, "texture"));
    }

    /** A decal of its tint alone, for a {@code CgDecalComponent}: translucent, lit by the world. */
    public static Builder decal(String id) {
        return new Builder(id, true, null, null);
    }

    /** A decal drawing {@code texture} across its box, for a {@code CgDecalComponent}: translucent, lit by the world. */
    public static Builder decal(String id, String texture) {
        return new Builder(id, true, Objects.requireNonNull(texture, "texture"), null);
    }

    /** Builds one base material: each call sets a property over the base's. */
    public static final class Builder {
        private final String id;
        private final boolean decal;
        @Nullable
        private final String textureId;
        @Nullable
        private final CgTexture texture;
        private CgBlendMode blend = CgBlendMode.OPAQUE;
        private float maskClip = Float.NaN;
        private boolean twoSided;
        private CgTranslucencyPass pass;
        private CgShadingModel shading = CgShadingModels.LIT;
        private float emissive = Float.NaN;
        @Nullable
        private Integer tint;
        private CgSampler.Filter filter = CgSampler.Filter.NEAREST;
        private CgSampler.Address address = CgSampler.Address.CLAMP;

        private Builder(String id, boolean decal, @Nullable String textureId, @Nullable CgTexture texture) {
            this.id = Objects.requireNonNull(id, "id");
            this.decal = decal;
            this.textureId = textureId;
            this.texture = texture;
        }

        /** How it blends: {@link CgBlendMode#OPAQUE} by default. Clears {@link #masked}. */
        public Builder blend(CgBlendMode blend) {
            surfaceOnly("blend");
            this.blend = Objects.requireNonNull(blend, "blend");
            maskClip = Float.NaN;
            return this;
        }

        /** Opaque where the texture's alpha reaches {@code clip}, nothing elsewhere: a cutout. Clears {@link #blend}. */
        public Builder masked(float clip) {
            surfaceOnly("masked");
            blend = CgBlendMode.OPAQUE;
            maskClip = clip;
            return this;
        }

        /** Both faces drawn: Unreal's two-sided, Minecraft's no-cull. */
        public Builder twoSided() {
            surfaceOnly("twoSided");
            twoSided = true;
            return this;
        }

        /** Its shading model: {@link CgShadingModels#LIT} (the world's light) by default, {@code UNLIT}, or a kit's. */
        public Builder shading(CgShadingModel model) {
            shading = Objects.requireNonNull(model, "model");
            return this;
        }

        /** The colour given off as light, times {@code scale}; under {@code UNLIT}, the colour drawn at that. */
        public Builder emissive(float scale) {
            emissive = scale;
            return this;
        }

        /** Multiplies the texture, alpha included, as {@code 0xAARRGGBB}. */
        public Builder tint(int argb) {
            tint = argb;
            return this;
        }

        /** Where its translucent draws are laid over the scene ({@link CgTranslucencyPass}). A translucent blend's only. */
        public Builder translucencyPass(CgTranslucencyPass pass) {
            surfaceOnly("translucencyPass");
            this.pass = Objects.requireNonNull(pass, "pass");
            return this;
        }

        /** How its texture is sampled, nearest and clamped by default. */
        public Builder sampling(CgSampler.Filter filter, CgSampler.Address address) {
            this.filter = Objects.requireNonNull(filter, "filter");
            this.address = Objects.requireNonNull(address, "address");
            return this;
        }

        /**
         * Its definition: what a prefab's part or a kit names.
         *
         * @throws IllegalStateException for a translucency pass on a blend that is not translucent
         */
        public CgMaterialDefinition definition() {
            boolean translucent = blend != CgBlendMode.OPAQUE;
            if (pass != null && !translucent) throw new IllegalStateException(id + ": a translucency pass is a translucent blend's");
            String family = decal ? "decal" : !Float.isNaN(maskClip) ? "masked" : translucent ? "translucent" : "opaque";
            String shader = SHADERS + family + (shading.base().equals("unlit") ? "_unlit" : "") + ".shader";
            CgMaterialDefinition.Builder d = CgMaterialDefinition.of(id, shader).shading(shading);
            if (!Float.isNaN(maskClip)) d.masked(maskClip);
            else if (translucent) d.blend(blend);
            if (twoSided) d.twoSided();
            if (pass != null) d.translucencyPass(pass);
            if (tint != null) d.color("_Tint", tint);
            if (!Float.isNaN(emissive)) d.value("_Emissive", emissive);
            if (textureId != null) d.texture("_MainTex", textureId, filter, address);
            else if (texture != null) d.texture("_MainTex", texture, filter, address);
            return d.build();
        }

        /** Its material, to draw with. */
        public CgMaterial build() {
            return CgMaterial.of(definition());
        }

        private void surfaceOnly(String what) {
            if (decal) throw new IllegalStateException(what + " is a surface's: a decal blends over what it lands on");
        }
    }
}
