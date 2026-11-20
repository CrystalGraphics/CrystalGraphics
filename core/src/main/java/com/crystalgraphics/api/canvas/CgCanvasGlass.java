package com.crystalgraphics.api.canvas;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInstances;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.precache.CgPipelineList;
import com.crystalgraphics.render.ui.backdrop.CgBackdrop;

/**
 * A pane's material: the canvas's glass element over a blurred and a sharp copy of what is behind it, its look a
 * {@link CgBackdropFilter}. One per pane drawn in a turn, since each writes its own instance.
 */
final class CgCanvasGlass {

    static final String SHADER = "crystalgraphics:shaders/ui/glass.shader";
    private static final String WITH_REFRACTION = "WITH_REFRACTION", WITH_CHROMATIC = "WITH_CHROMATIC",
            WITH_SPECULAR = "WITH_SPECULAR", WITH_NOISE = "WITH_NOISE";
    private static final String[] LAYERS = {WITH_REFRACTION, WITH_CHROMATIC, WITH_SPECULAR, WITH_NOISE};

    private static CgMaterial shared;

    /** An instance per set of layers its values switch on. */
    private final CgMaterialInstances instances = new CgMaterialInstances(material());
    private CgMaterialInstance current = instances.of(instances.material());

    private CgTexture blurred, sharp;
    private float width, height;
    private final float[] radii = new float[8];
    private int gridWidth = 1, gridHeight = 1;
    private float offsetX, offsetY;
    private int bx0, by0, bx1 = 1, by1 = 1;
    private CgBackdropFilter look = CgBackdropFilter.DEFAULT;

    /** The glass shader under its blend, made once: straight alpha over what is there, no depth. */
    private static synchronized CgMaterial material() {
        if (shared == null) {
            shared = CgMaterial.load(SHADER,
                    CgMaterialState.builder().blend(CgBlendMode.ALPHA).twoSided().disableDepthTest().build());
            shared.precache();
        }
        return shared;
    }

    /** Glass in every set of its layers: a pane's set follows its look, so each is drawn somewhere. */
    static void precache(CgPipelineList list) {
        for (int set = 0; set < 1 << LAYERS.length; set++) {
            CgMaterial m = material();
            for (int k = 0; k < LAYERS.length; k++) m = m.keyword(LAYERS[k], (set & 1 << k) != 0);
            list.addPass(m, CgInstanceKind.ELEMENT);
        }
    }

    /** Forgets the shared material, at a context's teardown. */
    static synchronized void release() {
        shared = null;
    }

    /** What its element draws under: the instance {@link #apply} wrote. */
    CgMaterialInstance instance() {
        return current;
    }

    /** The box, in the units its element is drawn in. */
    CgCanvasGlass size(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** Corner radii per corner and axis, CSS order (TL, TR, BR, BL). */
    CgCanvasGlass radii(float rxTL, float ryTL, float rxTR, float ryTR, float rxBR, float ryBR, float rxBL, float ryBL) {
        radii[0] = rxTL; radii[1] = ryTL; radii[2] = rxTR; radii[3] = ryTR;
        radii[4] = rxBR; radii[5] = ryBR; radii[6] = rxBL; radii[7] = ryBL;
        return this;
    }

    /** The same radius at every corner. */
    CgCanvasGlass radii(float radius) {
        return radii(radius, radius, radius, radius, radius, radius, radius, radius);
    }

    /**
     * What is behind it: where the bound target's pixel (0, 0) is in the backdrop's texels, and the rect of them it may
     * sample, bottom-left. A pane in a layer draws into another target than the one captured.
     */
    CgCanvasGlass backdrop(CgBackdrop backdrop, float offsetX, float offsetY, int x0, int y0, int x1, int y1) {
        this.blurred = backdrop.blurred();
        this.sharp = backdrop.sharp();
        this.gridWidth = Math.max(1, backdrop.gridWidth());
        this.gridHeight = Math.max(1, backdrop.gridHeight());
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.bx0 = x0; this.by0 = y0;
        this.bx1 = x1; this.by1 = y1;
        return this;
    }

    /** Every value of {@code filter} but its blur, which is the backdrop's. */
    CgCanvasGlass look(CgBackdropFilter filter) {
        this.look = filter;
        return this;
    }

    /** How far its lens samples past its edge, in its own units: what a backdrop captures around it. */
    float reach() {
        return refracts() ? 2.2f * look.bezel() * (1f + 0.2f * look.chromatic()) : 0f;
    }

    /** Writes its values into the instance of the layers they switch on. */
    CgCanvasGlass apply() {
        if (blurred == null || sharp == null) throw new IllegalStateException("glass without a backdrop");
        boolean refracts = refracts();
        current = instances.of(instances.material().keyword(WITH_REFRACTION, refracts)
                .keyword(WITH_CHROMATIC, refracts && look.chromatic() > 0f)
                .keyword(WITH_SPECULAR, look.specular() > 0f).keyword(WITH_NOISE, look.noise() > 0f));
        write(current);
        return this;
    }

    private boolean refracts() {
        return look.ior() > 1.001f && look.bezel() > 0f;
    }

    private void write(CgMaterialValues b) {
        b.sampler("_MainTex", 0, blurred);
        b.sampler("_SharpTex", 1, sharp);
        b.vec4("_CornerRadiusX", radii[0], radii[2], radii[4], radii[6]);
        b.vec4("_CornerRadiusY", radii[1], radii[3], radii[5], radii[7]);
        b.vec2("_BoxSize", width, height);
        b.vec2("_GridSize", gridWidth, gridHeight);
        b.vec2("_TargetOffset", offsetX, offsetY);
        // Half a texel in, so a bilinear tap never reaches past it
        b.vec4("_CaptureRect", (bx0 + 0.5f) / gridWidth, (by0 + 0.5f) / gridHeight, (bx1 - 0.5f) / gridWidth,
                (by1 - 0.5f) / gridHeight);
        b.colorARGB("_Tint", look.tint());
        b.set1f("_Saturation", look.saturation());
        b.set1f("_Luminosity", look.luminosity());
        b.set1f("_Bezel", look.bezel());
        b.set1f("_Ior", look.ior());
        b.set1f("_Specular", look.specular());
        b.set1f("_Glow", look.glow());
        b.set1f("_EdgeHighlight", look.rim());
        b.set1f("_EdgeWidth", look.rimWidth());
        b.set1f("_RimAmbient", look.rimAmbient());
        b.set1f("_Chromatic", look.chromatic());
        b.set1f("_Noise", look.noise());
        float lx = look.lightX(), ly = look.lightY(), length = (float) Math.sqrt(lx * lx + ly * ly);
        b.vec2("_LightDir", length > 0f ? lx / length : 0f, length > 0f ? ly / length : -1f);
    }
}
