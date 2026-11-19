package com.crystalgraphics.api.paint.effect;

/**
 * What a pane does to what is behind it: CSS's {@code backdrop-filter}, grown into glass. The backdrop is captured,
 * blurred, saturated, tinted and lit, then refracted through a lens at the pane's edge, split into colours, highlighted
 * and grained. A rect on the canvas or a translucent primitive in the world takes one, and either sees anything already
 * drawn behind it, other panes included.
 *
 * <pre>{@code
 * canvas.rect(x, y, w, h).radius(12).backdropFilter(CgBackdropFilter.ACRYLIC).submit();
 * canvas.rect(x, y, w, h).radius(24).backdropFilter(CgBackdropFilter.LIQUID.tint(0x20FFFFFF).light(0.3f, -1f)).submit();
 *
 * // in the world, its lengths in blocks: the look was tuned in pixels, a block 32 of them
 * scene.quad(2f, 1f).radii(0.25f).backdropFilter(CgBackdropFilter.FROSTED.scaled(1 / 32f)).at(x, y, z).spawn();
 * }</pre>
 *
 * <ul>
 *   <li>The blur is always in the target's pixels; the lens's bezel and the rim's width are in the shape's units, so
 *       {@link #scaled} fits a look tuned on screen to a pane in the world.</li>
 *   <li>Each pane captures what is behind it when its turn comes, so a pane over a pane shows the first through it;
 *       panes that do not overlap share one capture.</li>
 *   <li>A pane's picture is never kept across frames: what is behind it changes.</li>
 * </ul>
 *
 * @param blur       the backdrop's Gaussian sigma, in target pixels
 * @param saturation 1 unchanged, above 1 more vivid
 * @param tint       laid over the backdrop, straight {@code 0xAARRGGBB}
 * @param luminosity how much the tint's brightness replaces the backdrop's, 0 to 1
 * @param bezel      the lens's width at the edge, in the shape's units; 0 for none
 * @param ior        the lens's index of refraction: 1 is none, 1.5 glass
 * @param chromatic  how far apart the colour channels refract
 * @param specular   the highlight's strength, over glow and rim
 * @param glow       the broad highlight along the light axis
 * @param rim        the thin highlight band at the edge
 * @param rimWidth   that band's width, in the shape's units
 * @param rimAmbient how much of the rim is lit evenly: 0 directional, 1 even
 * @param noise      grain
 * @param lightX     the light's direction across, with {@code lightY} normalised when used; up-left by default
 * @param lightY     the light's direction down
 */
public record CgBackdropFilter(float blur, float saturation, int tint, float luminosity, float bezel, float ior,
                               float chromatic, float specular, float glow, float rim, float rimWidth,
                               float rimAmbient, float noise, float lightX, float lightY) {

    private static final float LIGHT = -0.7071f;

    /** A pane over a blur, a lens at its edge, lit and grained: what a pane is when nothing says otherwise. */
    public static final CgBackdropFilter DEFAULT = of(12f, 1.35f, 0x662B2D30, 0f, 8f, 1.5f, 0.20f, 1f, 0.10f, 0.25f,
            3f, 0.5f, 0.04f);
    /** Plain glass: no blur, a gentle lens, a faint highlight. */
    public static final CgBackdropFilter CLEAR = of(0f, 1f, 0x00000000, 0f, 10f, 1.35f, 0f, 0.6f, 0.05f, 0.25f, 3f,
            0.5f, 0f);
    /** Frosted: a wide blur under a light tint, no lens. */
    public static final CgBackdropFilter FROSTED = of(24f, 1.2f, 0x1FFFFFFF, 0f, 0f, 1.5f, 0.20f, 0.3f, 0.10f, 0.25f,
            3f, 0.5f, 0.05f);
    /** Apple's liquid glass: a little blur, a wide strong lens, a bright rim and some dispersion. */
    public static final CgBackdropFilter LIQUID = of(4f, 1.8f, 0x14FFFFFF, 0f, 20f, 1.6f, 0.25f, 1.2f, 0.2f, 0.4f, 3f,
            0.5f, 0.02f);
    /** Windows' acrylic: luminosity takes the tint's brightness, so a dark tint is a dark pane over anything. */
    public static final CgBackdropFilter ACRYLIC = of(30f, 1.5f, 0x401F1F23, 0.1f, 0f, 1.5f, 0.20f, 0f, 0f, 0.12f, 3f,
            0.5f, 0.03f);
    /** Windows' Mica: a very wide blur under a dark tint, its brightness mostly the tint's. */
    public static final CgBackdropFilter MICA = of(40f, 1.2f, 0xBF202124, 0.55f, 0f, 1.5f, 0.20f, 0f, 0f, 0f, 3f, 0.5f,
            0f);
    /**
     * A dispersive prism: dispersion rather than displacement. A stronger lens folds what is behind back on itself, and
     * a fringe that wide splits into hard coloured lines; a pixel of blur keeps it soft.
     */
    public static final CgBackdropFilter PRISM = of(1f, 1.2f, 0x00000000, 0f, 16f, 1.9f, 1.6f, 0.5f, 0.05f, 0.25f, 3f,
            0.5f, 0f);

    public CgBackdropFilter {
        for (float v : new float[]{blur, saturation, luminosity, bezel, chromatic, specular, glow, rim, rimWidth,
                rimAmbient, noise}) {
            if (!(v >= 0f) || Float.isInfinite(v)) {
                throw new IllegalArgumentException("a backdrop filter's lengths and strengths are finite and >= 0");
            }
        }
        if (!(ior >= 1f) || Float.isInfinite(ior)) throw new IllegalArgumentException("ior must be >= 1: " + ior);
        if (!Float.isFinite(lightX) || !Float.isFinite(lightY) || (lightX == 0f && lightY == 0f)) {
            throw new IllegalArgumentException("the light needs a direction: " + lightX + ", " + lightY);
        }
    }

    private static CgBackdropFilter of(float blur, float saturation, int tint, float luminosity, float bezel, float ior,
                                       float chromatic, float specular, float glow, float rim, float rimWidth,
                                       float rimAmbient, float noise) {
        return new CgBackdropFilter(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim,
                rimWidth, rimAmbient, noise, LIGHT, LIGHT);
    }

    /** This look with the backdrop blurred by {@code sigma} target pixels. */
    public CgBackdropFilter blur(float sigma) {
        return new CgBackdropFilter(sigma, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim,
                rimWidth, rimAmbient, noise, lightX, lightY);
    }

    /** This look with {@code argb} laid over the backdrop. */
    public CgBackdropFilter tint(int argb) {
        return new CgBackdropFilter(blur, saturation, argb, luminosity, bezel, ior, chromatic, specular, glow, rim,
                rimWidth, rimAmbient, noise, lightX, lightY);
    }

    /** This look with the backdrop's saturation and how much of its brightness the tint takes. */
    public CgBackdropFilter color(float saturation, float luminosity) {
        return new CgBackdropFilter(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim,
                rimWidth, rimAmbient, noise, lightX, lightY);
    }

    /** This look with a lens {@code bezel} wide at the edge, refracting by {@code ior}. */
    public CgBackdropFilter lens(float bezel, float ior) {
        return new CgBackdropFilter(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim,
                rimWidth, rimAmbient, noise, lightX, lightY);
    }

    /** This look with its colours refracting {@code chromatic} apart. */
    public CgBackdropFilter chromatic(float chromatic) {
        return new CgBackdropFilter(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim,
                rimWidth, rimAmbient, noise, lightX, lightY);
    }

    /** This look's highlights: overall strength, the broad glow, the rim band, its width and its even part. */
    public CgBackdropFilter highlight(float specular, float glow, float rim, float rimWidth, float rimAmbient) {
        return new CgBackdropFilter(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim,
                rimWidth, rimAmbient, noise, lightX, lightY);
    }

    /** This look with {@code noise} grain. */
    public CgBackdropFilter noise(float noise) {
        return new CgBackdropFilter(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim,
                rimWidth, rimAmbient, noise, lightX, lightY);
    }

    /** This look lit from ({@code x}, {@code y}), a direction on the target, y down. */
    public CgBackdropFilter light(float x, float y) {
        return new CgBackdropFilter(blur, saturation, tint, luminosity, bezel, ior, chromatic, specular, glow, rim,
                rimWidth, rimAmbient, noise, x, y);
    }

    /**
     * This look with its bezel and rim width times {@code unitsPerPixel}, for a shape measured in other units than
     * pixels: a pane in the world, a block {@code 1 / unitsPerPixel} of the look's pixels. The blur stays the target's.
     */
    public CgBackdropFilter scaled(float unitsPerPixel) {
        return new CgBackdropFilter(blur, saturation, tint, luminosity, bezel * unitsPerPixel, ior, chromatic, specular,
                glow, rim, rimWidth * unitsPerPixel, rimAmbient, noise, lightX, lightY);
    }

    /**
     * How far past the pane its lens reads, in the shape's units: the capture covers the pane grown by this, plus three
     * sigma of the blur in target pixels.
     */
    public float lensReach() {
        return ior > 1.001f && bezel > 0f ? 2.2f * bezel * (1f + 0.2f * chromatic) : 0f;
    }
}
