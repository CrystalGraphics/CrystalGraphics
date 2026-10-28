package com.crystalgraphics.render.post.volume;

import java.util.Objects;

/**
 * Screen-wide looks a {@link CgPostVolume} sets, Unity's Volume profile in small: each value a setter touched
 * overrides, the rest are left to whatever volume sits under it. The stack blends every volume's into one each firing.
 *
 * <pre>{@code
 * CgPostSettings burst = new CgPostSettings().flash(1.5f).bloom(2f);        // a stop and a half brighter, twice the glow
 * CgPostSettings hit = new CgPostSettings().impact(CgImpact.LINES, 1f).chromatic(0.8f);
 * CgPostSettings stage = new CgPostSettings().tonemap().filmHeal(0.18f).vignette(0.5f);   // Unreal 4.21's tonemapper
 * CgPostSettings cold = new CgPostSettings().grade(Region.SHADOWS, Control.GAIN, 0.8f, 0.9f, 1.2f, 1f)
 *         .grade(Region.GLOBAL, Control.SATURATION, 1f, 1f, 1f, 0.7f);              // Unreal's colour grading
 * }</pre>
 *
 * <ul>
 *   <li>Written from any thread, read on the render thread: floats, read at most a frame late.</li>
 *   <li>Neutral values are a look's absence: bloom 1, flash 0, vignette 0, chromatic 0, impact 0, fade 0, a neutral
 *       grade and white balance, and no tonemapper. A firing with none is drawn as it was, a host's pixels back byte
 *       for byte.</li>
 *   <li>Each grading control of each region is its own field, as Unreal's {@code bOverride_} flags are.</li>
 *   <li>The tonemapper's film settings are Unreal's, at its defaults, each its own field: a volume setting one moves
 *       that one alone, by its weight, as Unreal's {@code bOverride_} flags do.</li>
 *   <li>The focus (where chromatic aberration and speed lines centre) defaults to the volume's position on screen,
 *       or the screen's centre for a volume with none.</li>
 * </ul>
 */
public final class CgPostSettings {

    public static final int BLOOM = 1, FLASH = 1 << 1, VIGNETTE = 1 << 2, CHROMATIC = 1 << 3, IMPACT = 1 << 4, FOCUS = 1 << 5,
            FADE = 1 << 6, TONEMAP = 1 << 7, FILM_CONTRAST = 1 << 8, FILM_DYNAMIC_RANGE = 1 << 9, FILM_TOE = 1 << 10,
            FILM_HEAL = 1 << 11, FILM_SATURATION = 1 << 12, WHITE_BALANCE = 1 << 13;

    /** Where a grade applies: Unreal's global controls, then its Shadows, Midtones and Highlights. */
    public enum Region { GLOBAL, SHADOWS, MIDTONES, HIGHLIGHTS }

    /** A region's five controls, each an RGB and a scalar that multiplies it ({@code offset}'s adds). */
    public enum Control {
        SATURATION(1f), CONTRAST(1f), GAMMA(1f), GAIN(1f), OFFSET(0f);

        /** Its neutral value, in every component. */
        public final float neutral;

        Control(float neutral) {
            this.neutral = neutral;
        }
    }

    private static final Control[] CONTROLS = Control.values();
    /** Grade floats: four per control, five controls per region. */
    static final int GRADE_FLOATS = 4 * 5 * 4;
    /** The grade's override bit for {@code (region, control)}; {@link #TINT_BIT} for the scene tint. */
    private static final int TINT_BIT = 1 << 20;

    // Volatile: written on any thread, read on the render thread.
    volatile int overrides;
    volatile float bloom = 1f, flash, vignette, chromatic, impact;
    volatile float fade, fadeR, fadeG, fadeB;
    volatile CgImpactFrame impactLook = CgImpactFrame.NEGATIVE;
    volatile int impactSeed, impactSubject;
    volatile float focusX = 0.5f, focusY = 0.5f;
    volatile float filmContrast = 0.03f, filmDynamicRange = 4f, filmToe = 1f, filmHeal = 1f, filmSaturation = 1f;
    volatile float whiteTemp = 6500f, whiteTint;
    volatile int gradeOverrides;
    /** Each region's controls, {@code (region * 5 + control) * 4}: r, g, b, then the scalar. */
    final float[] grade = neutralGrade();
    volatile float tintR = 1f, tintG = 1f, tintB = 1f;
    /** Resolved only: the weight behind the focus so far, which averages it rather than pulling it to the centre. */
    private float focusWeight;

    /** Multiplies the bloom's intensity: 2 doubles the glow. */
    public CgPostSettings bloom(float scale) {
        bloom = Math.max(0f, scale);
        overrides |= BLOOM;
        return this;
    }

    /** Brightens the picture by {@code stops} of exposure: 1 doubles it. Emitted light is not brightened with it. */
    public CgPostSettings flash(float stops) {
        flash = stops;
        overrides |= FLASH;
        return this;
    }

    /**
     * Unreal's vignette ({@code VignetteIntensity}, 0 to 1): the screen darkened toward its corners by the lens's
     * cosine-fourth law, alike at every aspect ratio.
     */
    public CgPostSettings vignette(float amount) {
        vignette = clamp(amount);
        overrides |= VIGNETTE;
        return this;
    }

    /**
     * Fades the whole picture {@code amount} (0 to 1) of the way to {@code argb} ({@code 0xAARRGGBB}, its alpha unused),
     * as the last thing the composite does: a cut to black, a white-out. Unreal's camera fade.
     *
     * <pre>{@code
     * CgPostVolume black = CgPostStack.get().volume(100, new CgPostSettings().fade(0xFF000000, 1f));
     * black.weight(t);   // 0 to 1 over the fade
     * }</pre>
     */
    public CgPostSettings fade(int argb, float amount) {
        fadeR = (argb >> 16 & 0xFF) / 255f;
        fadeG = (argb >> 8 & 0xFF) / 255f;
        fadeB = (argb & 0xFF) / 255f;
        fade = clamp(amount);
        overrides |= FADE;
        return this;
    }

    /**
     * Unreal's colour fringe ({@code SceneFringeIntensity}), in percent, 0 for none and 5 its usual most: red and green
     * pulled in toward the focus, growing to the screen's edges.
     */
    public CgPostSettings chromatic(float amount) {
        chromatic = Math.max(0f, amount);
        overrides |= CHROMATIC;
        return this;
    }

    /**
     * Turns on Unreal 4.21's tonemapper over the HDR scene: the scene and bloom through its film curve and colour
     * table into the target's encoding, the vignette and fringe inside it, as its tonemap pass runs.
     *
     * <pre>{@code
     * CgPostStack.get().volume(0, new CgPostSettings().tonemap().filmHeal(0.18f).vignette(0.5f));   // a stage's look
     * }</pre>
     *
     * <ul>
     *   <li>It reads the HDR scene: with none it tonemaps the picture as decoded.</li>
     *   <li>Its curve is the legacy one ({@code r.TonemapperFilm 0}), with its film settings below.</li>
     * </ul>
     */
    public CgPostSettings tonemap() {
        overrides |= TONEMAP;
        return this;
    }

    /**
     * The legacy film curve: {@code FilmContrast} (0 to 1, default 0.03), {@code FilmDynamicRange} (stops, 1 to 4,
     * default 4), {@code FilmToeAmount} (0 to 1, default 1) and {@code FilmHealAmount} (0 to 1, default 1).
     */
    public CgPostSettings film(float contrast, float dynamicRange, float toe, float heal) {
        filmContrast = contrast;
        filmDynamicRange = dynamicRange;
        filmToe = toe;
        overrides |= FILM_CONTRAST | FILM_DYNAMIC_RANGE | FILM_TOE;
        return filmHeal(heal);
    }

    /** {@code FilmHealAmount}: how far toward white the shoulder heals, 0 to 1. */
    public CgPostSettings filmHeal(float heal) {
        filmHeal = heal;
        overrides |= FILM_HEAL;
        return this;
    }

    /** {@code FilmSaturation}, 0 to 2: 1 leaves colour as it is. */
    public CgPostSettings filmSaturation(float saturation) {
        filmSaturation = saturation;
        overrides |= FILM_SATURATION;
        return this;
    }

    /** Unreal's white balance: {@code WhiteTemp} in kelvin (6500 is none) and {@code WhiteTint} (-1 to 1). */
    public CgPostSettings whiteBalance(float temp, float tint) {
        whiteTemp = temp;
        whiteTint = tint;
        overrides |= WHITE_BALANCE;
        return this;
    }

    /**
     * One of Unreal's colour grading controls ({@code ColorGainShadows}, {@code ColorSaturation}...): {@code (r, g, b)}
     * and the scalar {@code w} that multiplies them, or for {@code OFFSET} adds to them.
     *
     * <ul>
     *   <li>A region's control is multiplied by the global one, or for {@code OFFSET} added to it.</li>
     *   <li>Shadows weigh fully below a luma of 0, none by 0.09; highlights from 0.5 to fully at 1; midtones the rest.</li>
     *   <li>It grades the HDR scene before the roll to white, or inside the tonemapper's table where it is on.</li>
     * </ul>
     */
    public CgPostSettings grade(Region region, Control control, float r, float g, float b, float w) {
        int at = (region.ordinal() * 5 + control.ordinal()) * 4;
        grade[at] = r;
        grade[at + 1] = g;
        grade[at + 2] = b;
        grade[at + 3] = w;
        gradeOverrides |= 1 << (region.ordinal() * 5 + control.ordinal());
        return this;
    }

    /**
     * Unreal's {@code SceneColorTint}: the picture multiplied by {@code (r, g, b)}, linear, after the tonemapper or
     * the roll to white.
     */
    public CgPostSettings tint(float r, float g, float b) {
        tintR = r;
        tintG = g;
        tintB = b;
        gradeOverrides |= TINT_BIT;
        return this;
    }

    /** {@link #tint(float, float, float)} from {@code 0xAARRGGBB}, times {@code intensity}; its alpha unused. */
    public CgPostSettings tint(int argb, float intensity) {
        return tint((argb >> 16 & 0xFF) / 255f * intensity, (argb >> 8 & 0xFF) / 255f * intensity, (argb & 0xFF) / 255f * intensity);
    }

    /** Turns the picture {@code amount} (0 to 1) of the way into preset {@code look}: an impact frame. */
    public CgPostSettings impact(CgImpact look, float amount) {
        return impact(Objects.requireNonNull(look, "look").frame(), amount, 0);
    }

    /**
     * Turns the picture {@code amount} (0 to 1) of the way into {@code look}, drawn from {@code seed}: a new seed draws
     * its lines and strokes anew ({@link CgImpactSequence#seed}).
     */
    public CgPostSettings impact(CgImpactFrame look, float amount, int seed) {
        impactLook = Objects.requireNonNull(look, "look");
        impactSeed = seed;
        impact = clamp(amount);
        overrides |= IMPACT;
        return this;
    }

    /**
     * What a drawn impact frame fills in ink: the glows of the world draws tagged {@code key}
     * ({@code CgWorldRenderer.Draw.subject}), which nothing else in the scene hides. 0, the default, is every visible
     * glow, which another effect's smoke may cover.
     *
     * <pre>{@code
     * settings.impact(look, 1f, seed).subject(key);
     * world.draw(dome, glow).at(x, y, z).subject(key).submit();
     * }</pre>
     */
    public CgPostSettings subject(int key) {
        impactSubject = key;
        return this;
    }

    /** Where on screen, 0 to 1 from the bottom left, aberration and speed lines centre; else the volume's position. */
    public CgPostSettings focus(float x, float y) {
        focusX = x;
        focusY = y;
        overrides |= FOCUS;
        return this;
    }

    /** Whether a setter set {@code bit}: the stack's. */
    public boolean overrides(int bit) {
        return (overrides & bit) != 0;
    }

    public float bloom() {
        return bloom;
    }

    public float flash() {
        return flash;
    }

    public float vignette() {
        return vignette;
    }

    public float chromatic() {
        return chromatic;
    }

    public float impact() {
        return impact;
    }

    /** How far the picture fades to {@link #fadeR()} and on, 0 to 1. */
    public float fade() {
        return fade;
    }

    /** The colour it fades to, 0 to 1, in the target's encoding. */
    public float fadeR() {
        return fadeR;
    }

    public float fadeG() {
        return fadeG;
    }

    public float fadeB() {
        return fadeB;
    }

    public CgImpactFrame impactLook() {
        return impactLook;
    }

    public int impactSeed() {
        return impactSeed;
    }

    public int impactSubject() {
        return impactSubject;
    }

    public float focusX() {
        return focusX;
    }

    public float focusY() {
        return focusY;
    }

    /** Whether the tonemapper is on. */
    public boolean tonemaps() {
        return (overrides & TONEMAP) != 0;
    }

    public float filmContrast() {
        return filmContrast;
    }

    public float filmDynamicRange() {
        return filmDynamicRange;
    }

    public float filmToe() {
        return filmToe;
    }

    public float filmHeal() {
        return filmHeal;
    }

    public float filmSaturation() {
        return filmSaturation;
    }

    public float whiteTemp() {
        return whiteTemp;
    }

    public float whiteTint() {
        return whiteTint;
    }

    /** {@code (region, control)}'s component {@code k}: r, g, b, then 3 for the scalar. */
    public float grade(Region region, Control control, int k) {
        return grade[(region.ordinal() * 5 + control.ordinal()) * 4 + k];
    }

    /** Whether {@code (region, control)} is set here. */
    public boolean grades(Region region, Control control) {
        return (gradeOverrides & 1 << (region.ordinal() * 5 + control.ordinal())) != 0;
    }

    /** Whether the scene tint is set here. */
    public boolean tints() {
        return (gradeOverrides & TINT_BIT) != 0;
    }

    public float tintR() {
        return tintR;
    }

    public float tintG() {
        return tintG;
    }

    public float tintB() {
        return tintB;
    }

    /** Whether the grade, the white balance or the tint is anything but neutral: what turns the composite's grade on. */
    public boolean grades() {
        if (whiteTemp != 6500f || whiteTint != 0f || tintR != 1f || tintG != 1f || tintB != 1f) return true;
        for (int i = 0; i < GRADE_FLOATS; i++) if (grade[i] != CONTROLS[i / 4 % 5].neutral) return true;
        return false;
    }

    private static float[] neutralGrade() {
        float[] g = new float[GRADE_FLOATS];
        for (int i = 0; i < GRADE_FLOATS; i++) g[i] = CONTROLS[i / 4 % 5].neutral;
        return g;
    }

    /** Back to no look at all, nothing overridden. */
    public void reset() {
        overrides = 0;
        bloom = 1f;
        flash = vignette = chromatic = impact = fade = fadeR = fadeG = fadeB = 0f;
        impactLook = CgImpactFrame.NEGATIVE;
        impactSeed = impactSubject = 0;
        focusX = focusY = 0.5f;
        focusWeight = 0f;
        filmContrast = 0.03f;
        filmDynamicRange = 4f;
        filmToe = filmHeal = filmSaturation = 1f;
        whiteTemp = 6500f;
        whiteTint = 0f;
        gradeOverrides = 0;
        for (int i = 0; i < GRADE_FLOATS; i++) grade[i] = CONTROLS[i / 4 % 5].neutral;
        tintR = tintG = tintB = 1f;
    }

    /**
     * Moves each value {@code from} overrides {@code weight} (0 to 1) of the way to it; a look takes the heavier's, and
     * the focus is the weighted average of every volume's that sets one.
     */
    public void blend(CgPostSettings from, float weight, float focusX, float focusY) {
        int o = from.overrides;
        if ((o & BLOOM) != 0) bloom += (from.bloom - bloom) * weight;
        if ((o & FLASH) != 0) flash += (from.flash - flash) * weight;
        if ((o & VIGNETTE) != 0) vignette += (from.vignette - vignette) * weight;
        if ((o & CHROMATIC) != 0) chromatic += (from.chromatic - chromatic) * weight;
        if ((o & FILM_CONTRAST) != 0) filmContrast += (from.filmContrast - filmContrast) * weight;
        if ((o & FILM_DYNAMIC_RANGE) != 0) filmDynamicRange += (from.filmDynamicRange - filmDynamicRange) * weight;
        if ((o & FILM_TOE) != 0) filmToe += (from.filmToe - filmToe) * weight;
        if ((o & FILM_HEAL) != 0) filmHeal += (from.filmHeal - filmHeal) * weight;
        if ((o & FILM_SATURATION) != 0) filmSaturation += (from.filmSaturation - filmSaturation) * weight;
        if ((o & WHITE_BALANCE) != 0) {
            whiteTemp += (from.whiteTemp - whiteTemp) * weight;
            whiteTint += (from.whiteTint - whiteTint) * weight;
        }
        int go = from.gradeOverrides;
        if (go != 0) {
            for (int c = 0; c < 20; c++) {
                if ((go & 1 << c) == 0) continue;
                for (int k = c * 4; k < c * 4 + 4; k++) grade[k] += (from.grade[k] - grade[k]) * weight;
            }
            if ((go & TINT_BIT) != 0) {
                tintR += (from.tintR - tintR) * weight;
                tintG += (from.tintG - tintG) * weight;
                tintB += (from.tintB - tintB) * weight;
            }
            gradeOverrides |= go;
        }
        if ((o & FADE) != 0) {
            // Over no fade yet, the colour is this volume's outright, not a mix from black.
            float t = fade > 0f ? weight : 1f;
            fadeR += (from.fadeR - fadeR) * t;
            fadeG += (from.fadeG - fadeG) * t;
            fadeB += (from.fadeB - fadeB) * t;
            fade += (from.fade - fade) * weight;
        }
        if ((o & IMPACT) != 0) {
            if (weight * from.impact >= impact) {
                impactLook = from.impactLook;
                impactSeed = from.impactSeed;
                impactSubject = from.impactSubject;
            }
            impact += (from.impact - impact) * weight;
        }
        if ((o & (CHROMATIC | IMPACT | FOCUS)) != 0 && weight > 0f) {
            // A position, so averaged by weight: one volume keeps its own focus however faint it is.
            float total = focusWeight + weight;
            this.focusX = (this.focusX * focusWeight + focusX * weight) / total;
            this.focusY = (this.focusY * focusWeight + focusY * weight) / total;
            focusWeight = total;
        }
        overrides |= o;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
