package com.crystalgraphics.render.post.settings;

import com.crystalgraphics.render.scene.component.CgPostVolume;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;

import java.util.Arrays;
import java.util.List;
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
 *   <li>The lens's settings (depth of field) override by bits of their own: {@link #overridesCamera}.</li>
 * </ul>
 *
 * <p>A lens focused 6 blocks out, wide open, five blades (Unreal's defaults but the focus):</p>
 *
 * <pre>{@code
 * CgPostSettings lens = new CgPostSettings().depthOfField(6f, 1.4f);
 * lens.depthOfFieldBladeCount(7).depthOfFieldMinFstop(1.2f).depthOfFieldSensorWidth(24.576f);
 * }</pre>
 *
 * <p>Motion blur, for a view whose {@code motionBlur} show flag is on: a whole frame's motion, up to a tenth of the
 * screen, a 60 fps shutter; or none in this volume's reach.</p>
 *
 * <pre>{@code
 * CgPostSettings rush = new CgPostSettings().motionBlur(1f, 10f).motionBlurTargetFps(60);
 * CgPostSettings still = new CgPostSettings().motionBlurAmount(0f);
 * }</pre>
 */
public final class CgPostSettings {

    public static final int BLOOM = 1, FLASH = 1 << 1, VIGNETTE = 1 << 2, CHROMATIC = 1 << 3, IMPACT = 1 << 4, FOCUS = 1 << 5,
            FADE = 1 << 6, TONEMAP = 1 << 7, FILM_CONTRAST = 1 << 8, FILM_DYNAMIC_RANGE = 1 << 9, FILM_TOE = 1 << 10,
            FILM_HEAL = 1 << 11, FILM_SATURATION = 1 << 12, WHITE_BALANCE = 1 << 13, FILMIC = 1 << 14,
            TONE_CURVE = 1 << 15, FILM_SLOPE = 1 << 16, FILM_CURVE_TOE = 1 << 17, FILM_SHOULDER = 1 << 18,
            FILM_BLACK_CLIP = 1 << 19, FILM_WHITE_CLIP = 1 << 20, BLUE_CORRECTION = 1 << 21, EXPAND_GAMUT = 1 << 22,
            DISPLAY_GAMMA = 1 << 23, GAUSSIAN = 1 << 24, BLOOM_THRESHOLD = 1 << 25, BLOOM_SIZE_SCALE = 1 << 26,
            BLOOM_EXCESS = 1 << 27, BLOOM_INTENSITY = 1 << 28, BLOOM_COLOUR = 1 << 29, BLOOM_CONSERVING = 1 << 30,
            BLOOM_EXCESS_THRESHOLD = 1 << 31;

    /** The lens's override bits ({@link #overridesCamera}): depth of field's focus, f-stop, least f-stop, blades, sensor. */
    public static final int DOF_FOCAL_DISTANCE = 1, DOF_FSTOP = 1 << 1, DOF_MIN_FSTOP = 1 << 2, DOF_BLADE_COUNT = 1 << 3,
            DOF_SENSOR_WIDTH = 1 << 4;
    /** Unreal's lens defaults: f/4, opening to f/1.2, five blades, a 24.576 mm sensor; no focus, so no blur. */
    public static final float DEFAULT_FSTOP = 4f, DEFAULT_MIN_FSTOP = 1.2f, DEFAULT_SENSOR_WIDTH = 24.576f;
    public static final int DEFAULT_BLADE_COUNT = 5;

    /** Motion blur's override bits ({@link #overridesMotionBlur}): its amount, longest blur, target frame rate, least size. */
    public static final int MB_AMOUNT = 1, MB_MAX = 1 << 1, MB_TARGET_FPS = 1 << 2, MB_PER_OBJECT_SIZE = 1 << 3;
    /**
     * Unreal's motion blur defaults ({@code FPostProcessSettings}): half a frame's motion, at most 5% of the screen's
     * width, a 30 fps camera's shutter, every object's velocity drawn. A view blurs once its {@code motionBlur} show flag
     * is on.
     */
    public static final float DEFAULT_MB_AMOUNT = 0.5f, DEFAULT_MB_MAX = 5f;
    public static final int DEFAULT_MB_TARGET_FPS = 30;
    /** Ambient occlusion's override bits ({@link #overridesAmbientOcclusion}). */
    public static final int AO_INTENSITY = 1, AO_RADIUS = 1 << 1, AO_POWER = 1 << 2, AO_BIAS = 1 << 3, AO_QUALITY = 1 << 4,
            AO_FADE_DISTANCE = 1 << 5, AO_FADE_RADIUS = 1 << 6, AO_RADIUS_IN_WORLD_SPACE = 1 << 7;
    /**
     * Unreal's ambient occlusion defaults ({@code FPostProcessSettings}), its centimetres as metres: half strength, a
     * radius of 2 (an eighth of the screen's height, off world space), power 2, bias 3, quality 50, faded out from 30 m to 80 m.
     */
    public static final float DEFAULT_AO_INTENSITY = 0.5f, DEFAULT_AO_RADIUS = 2f, DEFAULT_AO_POWER = 2f, DEFAULT_AO_BIAS = 3f,
            DEFAULT_AO_QUALITY = 50f, DEFAULT_AO_FADE_DISTANCE = 80f, DEFAULT_AO_FADE_RADIUS = 50f;

    /** Unreal's Gaussian bloom layers, {@code Bloom1} to {@code Bloom6}. */
    public static final int BLOOM_LAYERS = 6;
    /** Unreal 5.1's layer sizes, percent of the screen's width ({@code FPostProcessSettings}, Scene.cpp). */
    /** The bloom's intensity where nothing sets it. */
    public static final float DEFAULT_BLOOM_INTENSITY = 1f;

    private static final float[] LAYER_SIZES = {0.3f, 1f, 2f, 10f, 30f, 64f};
    /** And its layer tints, grey each. */
    private static final float[] LAYER_TINTS = {0.3465f, 0.138f, 0.1176f, 0.066f, 0.066f, 0.061f};

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
    /** Unreal 5.1's film settings ({@code FPostProcessSettings}' defaults), its tone curve's amount and gamut controls. */
    volatile float toneCurve = 1f, filmSlope = 0.88f, filmCurveToe = 0.55f, filmShoulder = 0.26f, filmBlackClip,
            filmWhiteClip = 0.04f, blueCorrection = 0.6f, expandGamut = 1f, displayGamma = 2.2f;
    /** How much of the picture the tonemapper takes, 0 to 1; the rest is the roll to white. */
    volatile float tonemapWeight;
    volatile float whiteTemp = 6500f, whiteTint;
    /** Unreal's {@code BloomThreshold} (-1, no setup pass) and {@code BloomSizeScale}. */
    volatile float bloomThreshold = -1f, bloomSizeScale = 4f;
    /** Each layer's size, then its tint's r, g, b: {@code layer * 4}. */
    final float[] bloomLayers = neutralLayers();
    /** Per layer, bit {@code 2 * layer} its size overridden, the next its tint. */
    volatile int layerOverrides;
    volatile int gradeOverrides;
    /** Each region's controls, {@code (region * 5 + control) * 4}: r, g, b, then the scalar. */
    final float[] grade = neutralGrade();
    volatile float tintR = 1f, tintG = 1f, tintB = 1f;
    /** The bloom's own: its intensity, its colour, energy-conserving or added, and the engine layers' threshold. */
    volatile float bloomIntensity = DEFAULT_BLOOM_INTENSITY, bloomR = 1f, bloomG = 1f, bloomB = 1f, bloomExcessThreshold = 1f;
    volatile boolean bloomConserving;
    /** The lens's: depth of field's focus distance in the host's units (0, none), f-stops, blades and sensor in mm. */
    volatile int cameraOverrides;
    volatile float dofFocalDistance, dofFstop = DEFAULT_FSTOP, dofMinFstop = DEFAULT_MIN_FSTOP, dofSensorWidth = DEFAULT_SENSOR_WIDTH;
    volatile int dofBladeCount = DEFAULT_BLADE_COUNT;
    /** Motion blur's: its amount (0-1), longest blur (percent of the width), target fps (0, the frame rate), least size. */
    volatile int motionBlurOverrides;
    volatile float mbAmount = DEFAULT_MB_AMOUNT, mbMax = DEFAULT_MB_MAX, mbPerObjectSize;
    volatile int mbTargetFps = DEFAULT_MB_TARGET_FPS;
    volatile int ambientOcclusionOverrides;
    volatile float aoIntensity = DEFAULT_AO_INTENSITY, aoRadius = DEFAULT_AO_RADIUS, aoPower = DEFAULT_AO_POWER,
            aoBias = DEFAULT_AO_BIAS, aoQuality = DEFAULT_AO_QUALITY, aoFadeDistance = DEFAULT_AO_FADE_DISTANCE,
            aoFadeRadius = DEFAULT_AO_FADE_RADIUS;
    volatile boolean aoRadiusInWorldSpace;
    /** Its weighted blendables, replaced whole. */
    private volatile Blendable[] blendables = NO_BLENDABLES;
    /** Resolved only: the weight behind the focus so far, which averages it rather than pulling it to the centre. */
    private float focusWeight;
    /** Resolved only: every volume's blendables that show, each at its weight times its volume's. Grown, never shrunk. */
    private Blendable[] drawn = NO_BLENDABLES;
    private float[] drawnWeights = new float[0];
    private int drawnCount;

    private static final Blendable[] NO_BLENDABLES = new Blendable[0];

    /**
     * A post material a volume draws at its weight (Unreal's {@code FWeightedBlendable}): its {@code material} at
     * {@code point}, {@code weight} times its volume's, among the effects there by {@code priority}. Made by
     * {@link #blendable}; the chain draws each through its own {@link CgPostMaterial}.
     */
    public static final class Blendable {
        private final CgMaterialInstance material;
        private final CgPostPoint point;
        private final float weight;
        private final int priority;
        private final CgPostMaterial drawer;

        private Blendable(CgMaterialInstance material, CgPostPoint point, float weight, int priority) {
            this.material = material;
            this.point = point;
            this.weight = weight;
            this.priority = priority;
            this.drawer = CgPostMaterial.drawer(material);
        }

        public CgMaterialInstance material() {
            return material;
        }

        public CgPostPoint point() {
            return point;
        }

        public float weight() {
            return weight;
        }

        public int priority() {
            return priority;
        }

        /** Engine side, the chain's: what draws it. */
        public CgPostMaterial drawer() {
            return drawer;
        }
    }

    /** Multiplies the bloom's intensity: 2 doubles the glow. */
    public CgPostSettings bloom(float scale) {
        bloom = Math.max(0f, scale);
        overrides |= BLOOM;
        return this;
    }

    /**
     * Draws {@code material}, a {@code #domain post} one, at {@code point} at {@code weight} times the volume's: Unreal's
     * weighted blendable. Among the effects at its point it runs by {@code priority}, ascending.
     *
     * <pre>{@code
     * scene.postVolume(new CgPostSettings().blendable(thermal, CgPostPoint.DISPLAY, 1f, 0)).spawn();
     * scene.postVolume(CgOutline.settings(CgOutline.material(1))).spawn();   // the engine's outline round stencil 1
     * }</pre>
     *
     * <ul>
     *   <li>Its instance is its own: a track setting its values changes this blendable alone.</li>
     *   <li>Blendables are not blended into one another: each volume's draws, by its own weight.</li>
     * </ul>
     */
    public CgPostSettings blendable(CgMaterialInstance material, CgPostPoint point, float weight, int priority) {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(point, "point");
        Blendable[] next = Arrays.copyOf(blendables, blendables.length + 1);
        next[next.length - 1] = new Blendable(material, point, Math.max(0f, Math.min(1f, weight)), priority);
        blendables = next;
        return this;
    }

    /** {@code material} at the {@code Location} its file names ({@code DISPLAY} where it names none), priority 0. */
    public CgPostSettings blendable(CgMaterialInstance material, float weight) {
        String location = CgMaterialProxy.of(material).postLocation();
        return blendable(material, location == null ? CgPostPoint.DISPLAY : CgPostPoint.valueOf(location), weight, 0);
    }

    /** Its blendables, as added. Read only. */
    public List<Blendable> blendables() {
        return Arrays.asList(blendables);
    }

    /** How many blendables it holds: with {@link #blendable(int)}, a walk that allocates nothing. */
    public int blendableCount() {
        return blendables.length;
    }

    /** Its {@code i}th blendable, as added. */
    public Blendable blendable(int i) {
        return blendables[i];
    }

    /** Resolved only: how many blendables show this frame. */
    public int drawnBlendables() {
        return drawnCount;
    }

    /** Resolved only: the {@code i}th blendable that shows. */
    public Blendable drawnBlendable(int i) {
        return drawn[i];
    }

    /** Resolved only: its weight this frame, its own times its volume's. */
    public float drawnWeight(int i) {
        return drawnWeights[i];
    }

    /**
     * The bloom's intensity (Unreal's {@code BloomIntensity}): 1 by default, 0 for none. A chain's base settings set
     * the engine's own ({@code CgPost.settings()}); a volume blends from there.
     */
    public CgPostSettings bloomIntensity(float intensity) {
        bloomIntensity = Math.max(0f, intensity);
        overrides |= BLOOM_INTENSITY;
        return this;
    }

    /** Multiplies the glow's colour: white by default. */
    public CgPostSettings bloomColour(float r, float g, float b) {
        bloomR = r;
        bloomG = g;
        bloomB = b;
        overrides |= BLOOM_COLOUR;
        return this;
    }

    /**
     * Lays the glow over the picture energy-conserving (Bevy's mode: {@code picture * (1 - i) + glow * i}, an intensity
     * of about 0.15 subtle) rather than adding it. The heavier volume's choice wins.
     */
    public CgPostSettings bloomConserving(boolean conserving) {
        bloomConserving = conserving;
        overrides |= BLOOM_CONSERVING;
        return this;
    }

    /**
     * Under the HDR scene, the linear brightness past which the engine's own layers bloom: 1 by default, the screen's
     * white. Unreal's layers bloom past {@link #bloomThreshold} instead.
     */
    public CgPostSettings bloomExcessThreshold(float threshold) {
        bloomExcessThreshold = Math.max(0f, threshold);
        overrides |= BLOOM_EXCESS_THRESHOLD;
        return this;
    }

    public float bloomIntensity() {
        return bloomIntensity;
    }

    public float bloomR() {
        return bloomR;
    }

    public float bloomG() {
        return bloomG;
    }

    public float bloomB() {
        return bloomB;
    }

    public boolean bloomConserving() {
        return bloomConserving;
    }

    public float bloomExcessThreshold() {
        return bloomExcessThreshold;
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
     * CgPostVolume black = scene.postVolume(new CgPostSettings().fade(0xFF000000, 1f)).priority(100).spawn();
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
     * scene.postVolume(new CgPostSettings().tonemap().filmHeal(0.18f).vignette(0.5f)).spawn();   // a stage's look
     * }</pre>
     *
     * <ul>
     *   <li>It reads the HDR scene: with none it tonemaps the picture as decoded.</li>
     *   <li>Its curve is the legacy one ({@code r.TonemapperFilm 0}), with its film settings below.</li>
     * </ul>
     */
    public CgPostSettings tonemap() {
        overrides |= TONEMAP;
        tonemapWeight = 1f;
        return this;
    }

    /**
     * Turns on Unreal 5.1's tonemapper over the HDR scene: its combined colour table with the ACES-fitted film curve
     * ({@code FilmToneMap}) taken {@link #toneCurve} of the way, then the sRGB encode, clipping each channel at 1.
     * Exposure scales the scene, bloom is added after, then the vignette, as 5.1's tonemap pass orders them.
     *
     * <pre>{@code
     * new CgPostSettings().filmic();                                  // Unreal 5.1's default look
     * new CgPostSettings().filmic().toneCurve(0f).blueCorrection(0f).expandGamut(0f);   // no curve: HDR clips at 1
     * }</pre>
     *
     * <ul>
     *   <li>Over a volume asking for 4.21's {@link #tonemap()}, this one's curve is drawn: one table a firing.</li>
     *   <li>A volume's weight fades the tonemapper in over the roll to white, rather than switching it.</li>
     * </ul>
     */
    public CgPostSettings filmic() {
        overrides |= TONEMAP | FILMIC;
        tonemapWeight = 1f;
        return this;
    }

    /** {@code ToneCurveAmount}, 0 to 1: how much of 5.1's film curve the table takes; 0 leaves it linear, clipped at 1. */
    public CgPostSettings toneCurve(float amount) {
        toneCurve = clamp(amount);
        overrides |= TONE_CURVE;
        return this;
    }

    /**
     * 5.1's film curve: {@code FilmSlope} (default 0.88), {@code FilmToe} (0.55), {@code FilmShoulder} (0.26),
     * {@code FilmBlackClip} (0) and {@code FilmWhiteClip} (0.04).
     */
    public CgPostSettings filmCurve(float slope, float toe, float shoulder, float blackClip, float whiteClip) {
        filmSlope = slope;
        filmCurveToe = toe;
        filmShoulder = shoulder;
        filmBlackClip = blackClip;
        filmWhiteClip = whiteClip;
        overrides |= FILM_SLOPE | FILM_CURVE_TOE | FILM_SHOULDER | FILM_BLACK_CLIP | FILM_WHITE_CLIP;
        return this;
    }

    /** {@code BlueCorrection}, 0 to 1 (default 0.6): bright blues kept from turning purple through the curve. */
    public CgPostSettings blueCorrection(float amount) {
        blueCorrection = clamp(amount);
        overrides |= BLUE_CORRECTION;
        return this;
    }

    /**
     * The display gamma 5.1's table encodes for (the render target's {@code GetDisplayGamma}, 2.2 by default): the
     * graded colour is raised to {@code 2.2 / gamma} before the sRGB encode, so a game set to 2.7 is brighter.
     */
    public CgPostSettings displayGamma(float gamma) {
        displayGamma = Math.max(0.1f, gamma);
        overrides |= DISPLAY_GAMMA;
        return this;
    }

    /** {@code ExpandGamut}, 0 to 1 (default 1): bright saturated colours pushed past sRGB's gamut toward P3. */
    public CgPostSettings expandGamut(float amount) {
        expandGamut = clamp(amount);
        overrides |= EXPAND_GAMUT;
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

    /**
     * Draws the bloom as Unreal's Gaussian layers ({@code BloomMethod} Standard): the scene halved down to a 64th, each
     * of six layers a Gaussian of {@code size} percent of the screen's width (times {@link #bloomSizeScale}) and a tint,
     * summed from the widest in, times {@link #bloom} over six: Unreal's look, where the engine's own layers ({@code CgBloom})
     * draw unless asked.
     *
     * <pre>{@code
     * new CgPostSettings().gaussianBloom().bloom(1.25f).bloomSizeScale(4f).bloomLayer(1, 0.3f, 0.3466f, 0.3466f, 0.3466f);
     * }</pre>
     *
     * <ul>
     *   <li>A layer of size 0 is skipped, as Unreal skips it.</li>
     *   <li>Under the HDR scene, its source is Unreal's bloom setup on the half-size scene ({@link #bloomThreshold}).</li>
     * </ul>
     */
    public CgPostSettings gaussianBloom() {
        overrides |= GAUSSIAN;
        return this;
    }

    /**
     * Unreal's layers bloom what the engine's own do: each channel's excess past {@link #bloomThreshold}, instead of Unreal's
     * setup, which scales the whole colour by how far its luminance passes the threshold. A yellow (3, 3, 0.8) blooms
     * (2, 2, 0) here, a deeper yellow than Unreal's.
     *
     * <pre>{@code
     * new CgPostSettings().gaussianBloom().bloomExcess().bloomThreshold(1f);
     * }</pre>
     *
     * <ul>
     *   <li>Read only under {@link #gaussianBloom}: the engine's own layers always bloom the excess.</li>
     *   <li>A threshold of -1 or less blooms the whole scene either way.</li>
     * </ul>
     */
    public CgPostSettings bloomExcess() {
        overrides |= BLOOM_EXCESS;
        return this;
    }

    /**
     * {@code BloomThreshold}: light whose luminance passes it blooms, by {@code saturate((luminance - t) / 2)}; -1 (the
     * default) or less blooms the whole scene, Unreal running no setup pass. The Gaussian bloom's alone.
     */
    public CgPostSettings bloomThreshold(float threshold) {
        bloomThreshold = threshold;
        overrides |= BLOOM_THRESHOLD;
        return this;
    }

    /** {@code BloomSizeScale} (default 4): every layer's size times it. */
    public CgPostSettings bloomSizeScale(float scale) {
        bloomSizeScale = Math.max(0f, scale);
        overrides |= BLOOM_SIZE_SCALE;
        return this;
    }

    /**
     * Layer {@code layer} (1 to 6, {@code Bloom1Size}/{@code Bloom1Tint} to {@code Bloom6}): its Gaussian's size in
     * percent of the screen's width and its tint. Unreal's defaults otherwise.
     */
    public CgPostSettings bloomLayer(int layer, float size, float r, float g, float b) {
        return bloomSize(layer, size).bloomTint(layer, r, g, b);
    }

    /** Layer {@code layer}'s size alone, its tint kept: {@link #bloomLayer}. */
    public CgPostSettings bloomSize(int layer, float size) {
        int at = layerAt(layer);
        bloomLayers[at] = Math.max(0f, size);
        layerOverrides |= 1 << 2 * (layer - 1);
        return this;
    }

    /** Layer {@code layer}'s tint alone: {@link #bloomLayer}. */
    public CgPostSettings bloomTint(int layer, float r, float g, float b) {
        int at = layerAt(layer);
        bloomLayers[at + 1] = r;
        bloomLayers[at + 2] = g;
        bloomLayers[at + 3] = b;
        layerOverrides |= 2 << 2 * (layer - 1);
        return this;
    }

    private static int layerAt(int layer) {
        if (layer < 1 || layer > BLOOM_LAYERS) throw new IllegalArgumentException("bloom layer " + layer + ": 1 to 6");
        return (layer - 1) * 4;
    }

    private static float[] neutralLayers() {
        float[] l = new float[BLOOM_LAYERS * 4];
        for (int i = 0; i < BLOOM_LAYERS; i++) {
            l[i * 4] = LAYER_SIZES[i];
            l[i * 4 + 1] = l[i * 4 + 2] = l[i * 4 + 3] = LAYER_TINTS[i];
        }
        return l;
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
     * its lines and strokes anew ({@code CgImpactSequence.seed}).
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
     * ({@code CgMeshCollector.subject}, a primitive's {@code subject}), which nothing else in the scene hides. 0, the default, is every visible
     * glow, which another effect's smoke may cover.
     *
     * <pre>{@code
     * settings.impact(look, 1f, seed).subject(key);
     * scene.once().mesh(dome, glow).at(x, y, z).subject(key).add();
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

    /** Whether a setter set the lens's {@code bit} ({@link #DOF_FOCAL_DISTANCE} and on). */
    public boolean overridesCamera(int bit) {
        return (cameraOverrides & bit) != 0;
    }

    /**
     * Depth of field focused {@code focalDistance} out, in the host's units, at f/{@code fstop}: Unreal's
     * {@code DepthOfFieldFocalDistance} and {@code DepthOfFieldFstop}. A focus of 0 is no blur.
     */
    public CgPostSettings depthOfField(float focalDistance, float fstop) {
        return depthOfFieldFocalDistance(focalDistance).depthOfFieldFstop(fstop);
    }

    /** Where the lens focuses, in the host's units; 0, none, and nothing blurs. */
    public CgPostSettings depthOfFieldFocalDistance(float distance) {
        dofFocalDistance = Math.max(0f, distance);
        cameraOverrides |= DOF_FOCAL_DISTANCE;
        return this;
    }

    /** The lens's f-stop: a smaller one opens the aperture and blurs more. */
    public CgPostSettings depthOfFieldFstop(float fstop) {
        dofFstop = Math.max(0.01f, fstop);
        cameraOverrides |= DOF_FSTOP;
        return this;
    }

    /** The f-stop the lens opens to, its blades out of the way: at it the blur is round, closing past it a polygon. */
    public CgPostSettings depthOfFieldMinFstop(float fstop) {
        dofMinFstop = Math.max(0.01f, fstop);
        cameraOverrides |= DOF_MIN_FSTOP;
        return this;
    }

    /** The aperture's blades, 4 to 16: the blur's polygon. */
    public CgPostSettings depthOfFieldBladeCount(int blades) {
        dofBladeCount = Math.max(4, Math.min(16, blades));
        cameraOverrides |= DOF_BLADE_COUNT;
        return this;
    }

    /** The sensor's width in millimetres: with the view's field of view, the lens's focal length. */
    public CgPostSettings depthOfFieldSensorWidth(float millimetres) {
        dofSensorWidth = Math.max(0.1f, millimetres);
        cameraOverrides |= DOF_SENSOR_WIDTH;
        return this;
    }

    /** Whether the lens blurs at all: it focuses somewhere. */
    public boolean depthOfFieldOn() {
        return dofFocalDistance > 0f;
    }

    public float depthOfFieldFocalDistance() {
        return dofFocalDistance;
    }

    public float depthOfFieldFstop() {
        return dofFstop;
    }

    public float depthOfFieldMinFstop() {
        return dofMinFstop;
    }

    public int depthOfFieldBladeCount() {
        return dofBladeCount;
    }

    public float depthOfFieldSensorWidth() {
        return dofSensorWidth;
    }

    /** Whether a setter set motion blur's {@code bit} ({@link #MB_AMOUNT} and on). */
    public boolean overridesMotionBlur(int bit) {
        return (motionBlurOverrides & bit) != 0;
    }

    /**
     * Motion blur of {@code amount} of a frame's motion (Unreal's {@code MotionBlurAmount}, 0 to 1), a blur at most
     * {@code max} percent of the screen's width long ({@code MotionBlurMax}).
     */
    public CgPostSettings motionBlur(float amount, float max) {
        return motionBlurAmount(amount).motionBlurMax(max);
    }

    /** How much of a frame's motion blurs, 0 to 1: the shutter's share of the frame. 0, none. */
    public CgPostSettings motionBlurAmount(float amount) {
        mbAmount = clamp(amount);
        motionBlurOverrides |= MB_AMOUNT;
        return this;
    }

    /** The longest blur, in percent of the screen's width (0 to 100). 0, none. */
    public CgPostSettings motionBlurMax(float percent) {
        mbMax = Math.max(0f, Math.min(100f, percent));
        motionBlurOverrides |= MB_MAX;
        return this;
    }

    /**
     * The frame rate whose shutter the blur is (Unreal's {@code MotionBlurTargetFPS}, 0 to 120): a game at 120 fps blurs
     * as a 30 fps camera would. 0 follows the frame rate, each frame its own motion.
     */
    public CgPostSettings motionBlurTargetFps(int fps) {
        mbTargetFps = Math.max(0, Math.min(120, fps));
        motionBlurOverrides |= MB_TARGET_FPS;
        return this;
    }

    /**
     * The least size, in percent of the screen, of an object whose own motion blurs (Unreal's
     * {@code MotionBlurPerObjectSize}): one smaller draws no velocity and moves with the camera alone.
     */
    public CgPostSettings motionBlurPerObjectSize(float percent) {
        mbPerObjectSize = Math.max(0f, percent);
        motionBlurOverrides |= MB_PER_OBJECT_SIZE;
        return this;
    }

    /** Whether motion blur blurs at all: Unreal's 0.001 floor on its amount and its longest blur. */
    public boolean motionBlurOn() {
        return mbAmount > 0.001f && mbMax > 0.001f;
    }

    public float motionBlurAmount() {
        return mbAmount;
    }

    public float motionBlurMax() {
        return mbMax;
    }

    public int motionBlurTargetFps() {
        return mbTargetFps;
    }

    public float motionBlurPerObjectSize() {
        return mbPerObjectSize;
    }

    /** Whether a setter set ambient occlusion's {@code bit} ({@link #AO_INTENSITY} and on). */
    public boolean overridesAmbientOcclusion(int bit) {
        return (ambientOcclusionOverrides & bit) != 0;
    }

    /** Ambient occlusion of {@code intensity} (0 to 1, Unreal's {@code AmbientOcclusionIntensity}) and {@code radius}. */
    public CgPostSettings ambientOcclusion(float intensity, float radius) {
        return ambientOcclusionIntensity(intensity).ambientOcclusionRadius(radius);
    }

    /** How far the occlusion darkens, 0 (none) to 1. */
    public CgPostSettings ambientOcclusionIntensity(float intensity) {
        aoIntensity = clamp(intensity);
        ambientOcclusionOverrides |= AO_INTENSITY;
        return this;
    }

    /**
     * How far it reaches: in world space, metres; else a share of the screen's height, 2 an eighth (Unreal's 200). GTAO
     * reaches its own 2 m ({@code r.GTAO.FalloffEnd}).
     */
    public CgPostSettings ambientOcclusionRadius(float radius) {
        aoRadius = Math.max(0f, radius);
        ambientOcclusionOverrides |= AO_RADIUS;
        return this;
    }

    /** Whether the radius is metres in the world rather than a share of the screen (Unreal's {@code AmbientOcclusionRadiusInWS}). */
    public CgPostSettings ambientOcclusionRadiusInWorldSpace(boolean world) {
        aoRadiusInWorldSpace = world;
        ambientOcclusionOverrides |= AO_RADIUS_IN_WORLD_SPACE;
        return this;
    }

    /** The occlusion's contrast, 0.1 to 8: its value raised to it before the intensity. */
    public CgPostSettings ambientOcclusionPower(float power) {
        aoPower = Math.max(0.1f, Math.min(8f, power));
        ambientOcclusionOverrides |= AO_POWER;
        return this;
    }

    /** SSAO's tolerance for a surface occluding itself, 0 to 10 (thousandths of its horizon). */
    public CgPostSettings ambientOcclusionBias(float bias) {
        aoBias = Math.max(0f, Math.min(10f, bias));
        ambientOcclusionOverrides |= AO_BIAS;
        return this;
    }

    /** Its samples, 0 to 100, under the player's most ({@code ambient_occlusion_max_quality}). */
    public CgPostSettings ambientOcclusionQuality(float quality) {
        aoQuality = Math.max(0f, Math.min(100f, quality));
        ambientOcclusionOverrides |= AO_QUALITY;
        return this;
    }

    /** The distance, metres, past which nothing is occluded; it fades in over {@link #ambientOcclusionFadeRadius} before. */
    public CgPostSettings ambientOcclusionFadeDistance(float metres) {
        aoFadeDistance = Math.max(0f, metres);
        ambientOcclusionOverrides |= AO_FADE_DISTANCE;
        return this;
    }

    /** How far, metres, before the fade distance the occlusion starts fading. */
    public CgPostSettings ambientOcclusionFadeRadius(float metres) {
        aoFadeRadius = Math.max(0f, metres);
        ambientOcclusionOverrides |= AO_FADE_RADIUS;
        return this;
    }

    /** Whether ambient occlusion darkens at all: Unreal's floors on its intensity and radius. */
    public boolean ambientOcclusionOn() {
        return aoIntensity > 0f && aoRadius >= 0.001f;
    }

    public float ambientOcclusionIntensity() {
        return aoIntensity;
    }

    public float ambientOcclusionRadius() {
        return aoRadius;
    }

    public boolean ambientOcclusionRadiusInWorldSpace() {
        return aoRadiusInWorldSpace;
    }

    public float ambientOcclusionPower() {
        return aoPower;
    }

    public float ambientOcclusionBias() {
        return aoBias;
    }

    public float ambientOcclusionQuality() {
        return aoQuality;
    }

    public float ambientOcclusionFadeDistance() {
        return aoFadeDistance;
    }

    public float ambientOcclusionFadeRadius() {
        return aoFadeRadius;
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

    /** Whether the tonemapper's curve is 5.1's ({@link #filmic}) rather than 4.21's legacy one. */
    public boolean tonemapsFilmic() {
        return (overrides & FILMIC) != 0;
    }

    /** How much of the picture the tonemapper takes over the roll to white, 0 to 1: its volumes' blended weight. */
    public float tonemapWeight() {
        return tonemapWeight;
    }

    public float toneCurve() {
        return toneCurve;
    }

    public float filmSlope() {
        return filmSlope;
    }

    /** 5.1's {@code FilmToe}; {@link #filmToe()} is the legacy curve's {@code FilmToeAmount}. */
    public float filmCurveToe() {
        return filmCurveToe;
    }

    public float filmShoulder() {
        return filmShoulder;
    }

    public float filmBlackClip() {
        return filmBlackClip;
    }

    public float filmWhiteClip() {
        return filmWhiteClip;
    }

    public float blueCorrection() {
        return blueCorrection;
    }

    public float expandGamut() {
        return expandGamut;
    }

    public float displayGamma() {
        return displayGamma;
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

    /** Whether the bloom is Unreal's layers and setup ({@link #gaussianBloom()}), not the engine's own layers. */
    public boolean bloomsGaussian() {
        return (overrides & GAUSSIAN) != 0;
    }

    /** Whether the Gaussian layers bloom each channel's excess ({@link #bloomExcess()}), not Unreal's setup. */
    public boolean bloomsExcess() {
        return (overrides & BLOOM_EXCESS) != 0;
    }

    public float bloomThreshold() {
        return bloomThreshold;
    }

    public float bloomSizeScale() {
        return bloomSizeScale;
    }

    /** Layer {@code layer}'s (1 to 6) size, percent of the screen's width. */
    public float bloomSize(int layer) {
        return bloomLayers[layerAt(layer)];
    }

    /** Component {@code c} (0 r, 1 g, 2 b) of layer {@code layer}'s tint. */
    public float bloomTint(int layer, int c) {
        return bloomLayers[layerAt(layer) + 1 + c];
    }

    /** Whether it overrides layer {@code layer}'s size ({@code tint} false) or tint. */
    public boolean overridesLayer(int layer, boolean tint) {
        layerAt(layer);
        return (layerOverrides & (tint ? 2 : 1) << 2 * (layer - 1)) != 0;
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

    /** Its own copy: every value and override, and its blendables, which stay shared until one is added. */
    public CgPostSettings copy() {
        CgPostSettings c = new CgPostSettings();
        c.blend(this, 1f, focusX, focusY);
        for (int i = 0; i < c.drawnCount; i++) c.drawn[i] = null;
        c.drawnCount = 0;
        c.focusWeight = 0f;
        c.bloomConserving = bloomConserving;
        c.impactLook = impactLook;
        c.impactSeed = impactSeed;
        c.impactSubject = impactSubject;
        c.blendables = blendables;
        return c;
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
        toneCurve = 1f;
        filmSlope = 0.88f;
        filmCurveToe = 0.55f;
        filmShoulder = 0.26f;
        filmBlackClip = 0f;
        filmWhiteClip = 0.04f;
        blueCorrection = 0.6f;
        expandGamut = 1f;
        displayGamma = 2.2f;
        tonemapWeight = 0f;
        whiteTemp = 6500f;
        whiteTint = 0f;
        gradeOverrides = 0;
        for (int i = 0; i < GRADE_FLOATS; i++) grade[i] = CONTROLS[i / 4 % 5].neutral;
        tintR = tintG = tintB = 1f;
        bloomThreshold = -1f;
        bloomSizeScale = 4f;
        layerOverrides = 0;
        System.arraycopy(NEUTRAL_LAYERS, 0, bloomLayers, 0, bloomLayers.length);
        bloomIntensity = DEFAULT_BLOOM_INTENSITY;
        bloomR = bloomG = bloomB = bloomExcessThreshold = 1f;
        bloomConserving = false;
        cameraOverrides = 0;
        dofFocalDistance = 0f;
        dofFstop = DEFAULT_FSTOP;
        dofMinFstop = DEFAULT_MIN_FSTOP;
        dofSensorWidth = DEFAULT_SENSOR_WIDTH;
        dofBladeCount = DEFAULT_BLADE_COUNT;
        motionBlurOverrides = 0;
        mbAmount = DEFAULT_MB_AMOUNT;
        mbMax = DEFAULT_MB_MAX;
        mbPerObjectSize = 0f;
        mbTargetFps = DEFAULT_MB_TARGET_FPS;
        ambientOcclusionOverrides = 0;
        aoIntensity = DEFAULT_AO_INTENSITY;
        aoRadius = DEFAULT_AO_RADIUS;
        aoPower = DEFAULT_AO_POWER;
        aoBias = DEFAULT_AO_BIAS;
        aoQuality = DEFAULT_AO_QUALITY;
        aoFadeDistance = DEFAULT_AO_FADE_DISTANCE;
        aoFadeRadius = DEFAULT_AO_FADE_RADIUS;
        aoRadiusInWorldSpace = false;
        for (int i = 0; i < drawnCount; i++) drawn[i] = null;
        drawnCount = 0;
    }

    private static final float[] NEUTRAL_LAYERS = neutralLayers();

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
        if ((o & TONEMAP) != 0) tonemapWeight += (from.tonemapWeight - tonemapWeight) * weight;
        if ((o & TONE_CURVE) != 0) toneCurve += (from.toneCurve - toneCurve) * weight;
        if ((o & FILM_SLOPE) != 0) filmSlope += (from.filmSlope - filmSlope) * weight;
        if ((o & FILM_CURVE_TOE) != 0) filmCurveToe += (from.filmCurveToe - filmCurveToe) * weight;
        if ((o & FILM_SHOULDER) != 0) filmShoulder += (from.filmShoulder - filmShoulder) * weight;
        if ((o & FILM_BLACK_CLIP) != 0) filmBlackClip += (from.filmBlackClip - filmBlackClip) * weight;
        if ((o & FILM_WHITE_CLIP) != 0) filmWhiteClip += (from.filmWhiteClip - filmWhiteClip) * weight;
        if ((o & BLUE_CORRECTION) != 0) blueCorrection += (from.blueCorrection - blueCorrection) * weight;
        if ((o & EXPAND_GAMUT) != 0) expandGamut += (from.expandGamut - expandGamut) * weight;
        if ((o & DISPLAY_GAMMA) != 0) displayGamma += (from.displayGamma - displayGamma) * weight;
        if ((o & BLOOM_THRESHOLD) != 0) bloomThreshold += (from.bloomThreshold - bloomThreshold) * weight;
        if ((o & BLOOM_SIZE_SCALE) != 0) bloomSizeScale += (from.bloomSizeScale - bloomSizeScale) * weight;
        if ((o & BLOOM_INTENSITY) != 0) bloomIntensity += (from.bloomIntensity - bloomIntensity) * weight;
        if ((o & BLOOM_EXCESS_THRESHOLD) != 0) bloomExcessThreshold += (from.bloomExcessThreshold - bloomExcessThreshold) * weight;
        if ((o & BLOOM_COLOUR) != 0) {
            bloomR += (from.bloomR - bloomR) * weight;
            bloomG += (from.bloomG - bloomG) * weight;
            bloomB += (from.bloomB - bloomB) * weight;
        }
        if ((o & BLOOM_CONSERVING) != 0 && weight >= 0.5f) bloomConserving = from.bloomConserving;
        int co = from.cameraOverrides;
        if (co != 0) {
            if ((co & DOF_FOCAL_DISTANCE) != 0) dofFocalDistance += (from.dofFocalDistance - dofFocalDistance) * weight;
            if ((co & DOF_FSTOP) != 0) dofFstop += (from.dofFstop - dofFstop) * weight;
            if ((co & DOF_MIN_FSTOP) != 0) dofMinFstop += (from.dofMinFstop - dofMinFstop) * weight;
            if ((co & DOF_SENSOR_WIDTH) != 0) dofSensorWidth += (from.dofSensorWidth - dofSensorWidth) * weight;
            if ((co & DOF_BLADE_COUNT) != 0 && weight >= 0.5f) dofBladeCount = from.dofBladeCount;
            cameraOverrides |= co;
        }
        int mo = from.motionBlurOverrides;
        if (mo != 0) {
            if ((mo & MB_AMOUNT) != 0) mbAmount += (from.mbAmount - mbAmount) * weight;
            if ((mo & MB_MAX) != 0) mbMax += (from.mbMax - mbMax) * weight;
            if ((mo & MB_PER_OBJECT_SIZE) != 0) mbPerObjectSize += (from.mbPerObjectSize - mbPerObjectSize) * weight;
            if ((mo & MB_TARGET_FPS) != 0 && weight >= 0.5f) mbTargetFps = from.mbTargetFps;
            motionBlurOverrides |= mo;
        }
        int ao = from.ambientOcclusionOverrides;
        if (ao != 0) {
            if ((ao & AO_INTENSITY) != 0) aoIntensity += (from.aoIntensity - aoIntensity) * weight;
            if ((ao & AO_RADIUS) != 0) aoRadius += (from.aoRadius - aoRadius) * weight;
            if ((ao & AO_POWER) != 0) aoPower += (from.aoPower - aoPower) * weight;
            if ((ao & AO_BIAS) != 0) aoBias += (from.aoBias - aoBias) * weight;
            if ((ao & AO_QUALITY) != 0) aoQuality += (from.aoQuality - aoQuality) * weight;
            if ((ao & AO_FADE_DISTANCE) != 0) aoFadeDistance += (from.aoFadeDistance - aoFadeDistance) * weight;
            if ((ao & AO_FADE_RADIUS) != 0) aoFadeRadius += (from.aoFadeRadius - aoFadeRadius) * weight;
            if ((ao & AO_RADIUS_IN_WORLD_SPACE) != 0 && weight >= 0.5f) aoRadiusInWorldSpace = from.aoRadiusInWorldSpace;
            ambientOcclusionOverrides |= ao;
        }
        Blendable[] bs = from.blendables;
        for (Blendable b : bs) {
            float w = b.weight * weight;
            if (w > 0f) addDrawn(b, w);
        }
        int lo = from.layerOverrides;
        if (lo != 0) {
            for (int l = 0; l < BLOOM_LAYERS; l++) {
                if ((lo & 1 << 2 * l) != 0) bloomLayers[l * 4] += (from.bloomLayers[l * 4] - bloomLayers[l * 4]) * weight;
                if ((lo & 2 << 2 * l) != 0) {
                    for (int k = l * 4 + 1; k < l * 4 + 4; k++) bloomLayers[k] += (from.bloomLayers[k] - bloomLayers[k]) * weight;
                }
            }
            layerOverrides |= lo;
        }
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

    private void addDrawn(Blendable b, float weight) {
        if (drawnCount == drawn.length) {
            int n = Math.max(4, drawnCount * 2);
            drawn = Arrays.copyOf(drawn, n);
            drawnWeights = Arrays.copyOf(drawnWeights, n);
        }
        drawn[drawnCount] = b;
        drawnWeights[drawnCount++] = weight;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
