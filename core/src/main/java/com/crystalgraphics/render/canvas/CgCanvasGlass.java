package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.api.texture.CgTexture;

import java.util.function.Consumer;

/**
 * A pane of glass over what is behind it: a blurred and a sharp copy of the backdrop, refracted through a lens at the
 * bezel, saturated, tinted toward a colour's brightness and colour, lit at the rim and grained. Windows' acrylic with
 * a lens (the liquid glass look). Set it up for a box, then draw a quad over the box under {@link #material()}.
 *
 * <pre>{@code
 * CgCanvasGlass pane = new CgCanvasGlass(CgCanvasMaterials.SCREEN);   // once: it owns its material
 *
 * pane.size(w, h).radii(12, 12, 12, 12, 12, 12, 12, 12)
 *     .backdrop(blurred, sharp)                                     // two copies of what is behind it
 *     .backdropMap(u0, v0, w / texW, 0f, 0f, -h / texH)             // where the box's corner and axes land in them
 *     .captureRect(cu0, cv0, cu1, cv1)                              // the part of them it may sample
 *     .look(CgCanvasGlass.LIQUID)                     // CLEAR, FROSTED, LIQUID, ACRYLIC, MICA, PRISM
 *     .apply();
 * canvas.quad(pane.material()).at(x, y).size(w, h).submit();
 *
 * pane.look(CgCanvasGlass.FROSTED).tint(0x40FFE0C0).apply();        // a preset, then a value of your own
 * pane.lens(0f, 1f).specular(0f).noise(0f).apply();                 // those layers compile out
 * }</pre>
 *
 * <p>A look's {@link Look#blur()} is the radius its blurred backdrop is made with: the glass samples the blur it is
 * given.</p>
 *
 * <ul>
 *   <li>The backdrops are textures the caller made: a capture of the target behind the box and a blur of it. Both
 *       hold premultiplied colour, and the glass covers whatever is transparent in them.</li>
 *   <li>The map is affine, from the box's uv (0..1, top-left origin) to the backdrops' uv: a rotated box reads a
 *       rotated patch. A capture of a framebuffer runs v upward, so its {@code uy} is negative.</li>
 *   <li>A lens samples past the box's edge, by about {@code 2.2 * bezel * (1 + 0.2 * chromatic)}: capture that much
 *       around it. Past the capture rect a tap folds back in once.</li>
 *   <li>{@link #apply} writes its material for the next quad; one instance per drawer.</li>
 *   <li>A layer whose value is 0 ({@code ior} 1 or {@code bezel} 0, {@code chromatic}, {@code specular},
 *       {@code noise}) is compiled out.</li>
 * </ul>
 */
public final class CgCanvasGlass {

    /**
     * Every value of a pane, as one record: what {@link #look} sets, and the blur its backdrop should be made with.
     *
     * <pre>{@code
     * pane.look(CgCanvasGlass.LIQUID);                                   // a preset
     * pane.look(CgCanvasGlass.FROSTED.withTint(0x40FFE0C0));             // a preset, warmer
     * blurred = blur(capture, CgCanvasGlass.LIQUID.blur());              // the caller's blur, in pixels
     * }</pre>
     *
     * @param blur       the backdrop's blur radius in pixels, for whoever makes the blurred backdrop: three sigma
     * @param saturation the backdrop's colour after the blur: 1 leaves it
     * @param tint       straight ARGB laid over the backdrop by its alpha
     * @param luminosity how much of the backdrop's brightness the tint's replaces, 0..1, its hue kept
     * @param bezel      the lens's width inward from the edge, in pixels; 0 is no lens
     * @param ior        the lens's index of refraction: 1 is none, 1.5 glass
     * @param chromatic  how far apart the colour channels refract
     * @param specular   the highlight's strength, over glow and rim
     * @param glow       the broad highlight along the light axis
     * @param rim        the thin highlight band at the edge
     * @param rimWidth   that band's width in pixels
     * @param rimAmbient how much of the rim is lit evenly: 0 directional, 1 even
     * @param noise      grain
     */
    public record Look(float blur, float saturation, int tint, float luminosity, float bezel, float ior,
                       float chromatic, float specular, float glow, float rim, float rimWidth, float rimAmbient,
                       float noise) {

        public Look withBlur(float blur) {
            return new Look(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim, rimWidth,
                    rimAmbient, noise);
        }

        public Look withTint(int tint) {
            return new Look(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim, rimWidth,
                    rimAmbient, noise);
        }
    }

    /** A pane over a blur, a lens at its edge, lit and grained: what a pane is when nothing says otherwise. */
    public static final Look DEFAULT = new Look(12f, 1.35f, 0x662B2D30, 0f, 8f, 1.5f, 0.20f, 1f, 0.10f, 0.25f, 3f,
            0.5f, 0.04f);
    /** Plain glass: no blur, a gentle lens, a faint highlight. */
    public static final Look CLEAR = new Look(0f, 1f, 0x00000000, 0f, 10f, 1.35f, 0f, 0.6f, 0.05f, 0.25f, 3f, 0.5f, 0f);
    /** Frosted: a wide blur under a light tint, no lens. */
    public static final Look FROSTED = new Look(24f, 1.2f, 0x1FFFFFFF, 0f, 0f, 1.5f, 0.20f, 0.3f, 0.10f, 0.25f, 3f,
            0.5f, 0.05f);
    /** Apple's liquid glass: a little blur, a wide strong lens, a bright rim and some dispersion. */
    public static final Look LIQUID = new Look(4f, 1.8f, 0x14FFFFFF, 0f, 20f, 1.6f, 0.25f, 1.2f, 0.2f, 0.4f, 3f, 0.5f,
            0.02f);
    /** Windows' acrylic. Luminosity takes the tint's brightness: much of a dark tint is a dark pane over anything. */
    public static final Look ACRYLIC = new Look(30f, 1.5f, 0x401F1F23, 0.1f, 0f, 1.5f, 0.20f, 0f, 0f, 0.12f, 3f, 0.5f,
            0.03f);
    /** Windows' Mica: a very wide blur under a dark tint, its brightness mostly the tint's. */
    public static final Look MICA = new Look(40f, 1.2f, 0xBF202124, 0.55f, 0f, 1.5f, 0.20f, 0f, 0f, 0f, 3f, 0.5f, 0f);
    /**
     * A dispersive prism: dispersion rather than displacement. A stronger lens folds what is behind back on itself, and
     * a fringe that wide splits into hard coloured lines; a pixel of blur keeps it soft.
     */
    public static final Look PRISM = new Look(1f, 1.2f, 0x00000000, 0f, 16f, 1.9f, 1.6f, 0.5f, 0.05f, 0.25f, 3f, 0.5f,
            0f);

    static final String WITH_REFRACTION = "WITH_REFRACTION", WITH_CHROMATIC = "WITH_CHROMATIC",
            WITH_SPECULAR = "WITH_SPECULAR", WITH_NOISE = "WITH_NOISE";

    private final CgMaterial material;
    private final Consumer<CgMaterialValues> writer = this::write;

    private CgTexture blurred, sharp;
    private float width, height;
    private final float[] radii = new float[8];
    private float u0, v0 = 1f, ux = 1f, vx, uy, vy = -1f;
    private float cu0, cv0, cu1 = 1f, cv1 = 1f;
    private int tintArgb = DEFAULT.tint();
    private float saturation = DEFAULT.saturation(), luminosity = DEFAULT.luminosity();
    private float bezel = DEFAULT.bezel(), ior = DEFAULT.ior(), chromatic = DEFAULT.chromatic();
    private float specular = DEFAULT.specular(), glow = DEFAULT.glow(), edgeHighlight = DEFAULT.rim(),
            edgeWidth = DEFAULT.rimWidth(), rimAmbient = DEFAULT.rimAmbient();
    private float noise = DEFAULT.noise();
    private float lightX = -0.7071f, lightY = -0.7071f;

    public CgCanvasGlass(CgCanvasMaterials materials) {
        this.material = materials.newGlass();
    }

    /** What its quad draws under. */
    public CgMaterial material() {
        return material;
    }

    /** The box, in the units its quad is drawn in. */
    public CgCanvasGlass size(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** Corner radii per corner and axis, CSS order (TL, TR, BR, BL). */
    public CgCanvasGlass radii(float rxTL, float ryTL, float rxTR, float ryTR,
                               float rxBR, float ryBR, float rxBL, float ryBL) {
        radii[0] = rxTL; radii[1] = ryTL; radii[2] = rxTR; radii[3] = ryTR;
        radii[4] = rxBR; radii[5] = ryBR; radii[6] = rxBL; radii[7] = ryBL;
        return this;
    }

    /** The blurred and the sharp copy of what is behind it: the interior shows the first, the bezel the second. */
    public CgCanvasGlass backdrop(CgTexture blurred, CgTexture sharp) {
        this.blurred = blurred;
        this.sharp = sharp;
        return this;
    }

    /** The box's uv origin in the backdrops' uv, and the uv steps across its width and down its height. */
    public CgCanvasGlass backdropMap(float u0, float v0, float ux, float vx, float uy, float vy) {
        this.u0 = u0; this.v0 = v0;
        this.ux = ux; this.vx = vx;
        this.uy = uy; this.vy = vy;
        return this;
    }

    /** The part of the backdrops it may sample, in their uv: {@code (u0, vBottom, u1, vTop)}. */
    public CgCanvasGlass captureRect(float u0, float vBottom, float u1, float vTop) {
        this.cu0 = u0; this.cv0 = vBottom;
        this.cu1 = u1; this.cv1 = vTop;
        return this;
    }

    /** Every value of {@code look} but its blur, which is the backdrop's maker's. */
    public CgCanvasGlass look(Look look) {
        return tint(look.tint()).saturation(look.saturation()).luminosity(look.luminosity())
                .lens(look.bezel(), look.ior()).chromatic(look.chromatic()).specular(look.specular())
                .highlight(look.glow(), look.rim(), look.rimWidth(), look.rimAmbient()).noise(look.noise());
    }

    /** Straight ARGB laid over the backdrop, by its alpha. */
    public CgCanvasGlass tint(int argb) {
        this.tintArgb = argb;
        return this;
    }

    /** The backdrop's colour after the blur: 1 leaves it, above 1 brings back what the blur greyed. */
    public CgCanvasGlass saturation(float saturation) {
        this.saturation = saturation;
        return this;
    }

    /** How much of the backdrop's brightness the tint's replaces, 0..1, its hue kept: acrylic 0.96, Mica 1. */
    public CgCanvasGlass luminosity(float luminosity) {
        this.luminosity = luminosity;
        return this;
    }

    /** The lens: the refracting band's width inward from the edge, and its index of refraction (1 is none, 1.5 glass). */
    public CgCanvasGlass lens(float bezel, float ior) {
        this.bezel = bezel;
        this.ior = ior;
        return this;
    }

    /** How far apart the colour channels refract: two more lens taps when above 0. */
    public CgCanvasGlass chromatic(float chromatic) {
        this.chromatic = chromatic;
        return this;
    }

    /** The highlight's strength, scaling the glow and the rim. */
    public CgCanvasGlass specular(float specular) {
        this.specular = specular;
        return this;
    }

    /**
     * The highlight's parts: a broad glow along the light axis, a thin rim band at the edge {@code width} pixels wide,
     * and how much of the rim is lit evenly rather than from the light (0 directional, 1 even).
     */
    public CgCanvasGlass highlight(float glow, float rim, float width, float rimAmbient) {
        this.glow = glow;
        this.edgeHighlight = rim;
        this.edgeWidth = width;
        this.rimAmbient = rimAmbient;
        return this;
    }

    /** Grain, which makes a flat backdrop read as a material. */
    public CgCanvasGlass noise(float noise) {
        this.noise = noise;
        return this;
    }

    /** Where the light comes from, in the box's space: up and to the left by default, so it moves with the box. */
    public CgCanvasGlass light(float x, float y) {
        float length = (float) Math.sqrt(x * x + y * y);
        this.lightX = length > 0f ? x / length : 0f;
        this.lightY = length > 0f ? y / length : -1f;
        return this;
    }

    /** Writes it into its material, for the next quad. */
    public CgCanvasGlass apply() {
        if (blurred == null || sharp == null) throw new IllegalStateException("glass without a backdrop");
        boolean refracts = ior > 1.001f && bezel > 0f;
        material.toggleKeyword(WITH_REFRACTION, refracts);
        material.toggleKeyword(WITH_CHROMATIC, refracts && chromatic > 0f);
        material.toggleKeyword(WITH_SPECULAR, specular > 0f);
        material.toggleKeyword(WITH_NOISE, noise > 0f);
        material.applyProperties(writer);
        return this;
    }

    private void write(CgMaterialValues b) {
        b.sampler("_MainTex", 0, blurred);
        b.sampler("_SharpTex", 1, sharp);
        b.vec4("_CornerRadiusX", radii[0], radii[2], radii[4], radii[6]);
        b.vec4("_CornerRadiusY", radii[1], radii[3], radii[5], radii[7]);
        b.vec2("_BoxSize", width, height);
        b.vec2("_BackdropOrigin", u0, v0);
        b.vec2("_BackdropAxisX", ux, vx);
        b.vec2("_BackdropAxisY", uy, vy);
        b.vec4("_CaptureRect", cu0, cv0, cu1, cv1);
        b.colorARGB("_Tint", tintArgb);
        b.set1f("_Saturation", saturation);
        b.set1f("_Luminosity", luminosity);
        b.set1f("_Bezel", bezel);
        b.set1f("_Ior", ior);
        b.set1f("_Specular", specular);
        b.set1f("_Glow", glow);
        b.set1f("_EdgeHighlight", edgeHighlight);
        b.set1f("_EdgeWidth", edgeWidth);
        b.set1f("_RimAmbient", rimAmbient);
        b.set1f("_Chromatic", chromatic);
        b.set1f("_Noise", noise);
        b.vec2("_LightDir", lightX, lightY);
    }
}
