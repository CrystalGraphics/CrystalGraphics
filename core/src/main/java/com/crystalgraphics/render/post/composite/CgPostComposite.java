package com.crystalgraphics.render.post.composite;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.post.volume.CgImpact;
import com.crystalgraphics.render.post.volume.CgImpactFrame;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;
import java.util.Arrays;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInstances;

/**
 * The post stack's one composite pass: every screen-wide look of the firing laid over the target in a single
 * full-screen triangle, as production engines compose bloom inside their tonemap or uber pass. Effects at
 * {@code BEFORE_COMPOSITE} set its inputs; the stack records it once, between that point and {@code AFTER_COMPOSITE}.
 *
 * <pre>{@code
 * // an effect, at BEFORE_COMPOSITE
 * post.composite().bloom(chain, 1.2f, 1f, 0.9f, 0.8f, false);   // a warm additive glow
 * post.composite().linear();                                     // this firing composites in linear light
 * post.composite().flash(2f).vignette(0.4f);                     // twice as bright, darker corners
 * post.composite().impact(CgImpact.LINES, 1f, 0.5f, 0.5f);       // focus lines from the centre
 * }</pre>
 *
 * <ul>
 *   <li>Each input switches on a {@link CgCompositeFeature}; a firing with none records nothing.</li>
 *   <li>The form ({@link CgCompositeForm}) is {@code BLEND} unless an input asks for {@code COPY}, as every look but
 *       bloom does.</li>
 *   <li>Inputs last one firing: the stack clears them before its effects record.</li>
 * </ul>
 */
public final class CgPostComposite {

    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU = CgGpuTrace.name("post.composite");
    private static final CgCompositeFeature[] FEATURES = CgCompositeFeature.values();
    private static final CgCompositeForm[] FORMS = CgCompositeForm.values();
    /** The grade's vec4 properties, in the order {@code CgColorGrade} packs them. */
    private static final String[] GRADE = {"_GradeIn0", "_GradeIn1", "_GradeIn2", "_GradeOut0", "_GradeOut1", "_GradeOut2",
            "_GradeS0", "_GradeS1", "_GradeS2", "_GradeS3", "_GradeS4", "_GradeM0", "_GradeM1", "_GradeM2", "_GradeM3",
            "_GradeM4", "_GradeH0", "_GradeH1", "_GradeH2", "_GradeH3", "_GradeH4", "_GradeRange", "_GradeTint"};
    /** The keyword set's bit for {@code SCENE}, above every feature's. */
    private static final int SCENE_KEY = 1 << 30, SUBJECT_KEY = 1 << 29;

    /**
     * Per form: an instance per keyword variant, the one its features draw, the features it was chosen for, and what its
     * properties were last set to.
     */
    private final CgMaterialInstances[] instances = new CgMaterialInstances[FORMS.length];
    private final CgMaterialInstance[] current = new CgMaterialInstance[FORMS.length];
    private final int[] keyed = {-1, -1};
    private final Inputs[] set = {new Inputs(), new Inputs()};

    /** This firing's inputs, as bits by feature ordinal, and whether it asked for linear light. */
    private int active;
    private boolean copy;
    private final Inputs inputs = new Inputs();
    private static final int LAYER_SLOTS = 2;
    private static final String[] LAYER = {"_Layer0", "_Layer1"}, LAYER_BLOOM = {"_Layer0Bloom", "_Layer1Bloom"},
            LAYER_LUT = {"_Layer0Lut", "_Layer1Lut"}, LAYER_PARAMS = {"_LayerParams0", "_LayerParams1"};

    /** Writes the inputs into the chosen variant's instance. */
    private void write(CgMaterialValues b) {
        if (inputs.scene != null) b.sampler("_Scene", 0, inputs.scene);
        if (inputs.bloom != null) b.sampler("_Bloom", 0, inputs.bloom);   // a look without bloom leaves it unread
        if (inputs.subject != null) b.sampler("_Subject", 0, inputs.subject);
        if (inputs.lut != null) b.sampler("_FilmLut", 0, inputs.lut);
        b.vec4("_Tonemap", inputs.tonemapWeight, inputs.filmic ? 1f : 0f, 0f, 0f);
        CgImpactFrame f = inputs.frame;
        b.set1f("_Intensity", inputs.intensity).vec4("_Tint", inputs.r, inputs.g, inputs.b, 1f)
                .set1f("_Conserve", inputs.conserve ? 1f : 0f).set1f("_Exposure", inputs.exposure)
                .set1f("_Vignette", inputs.vignette).set1f("_Chromatic", inputs.chromatic)
                .vec4("_Impact", inputs.impact, f.look(), inputs.seed, f.paper() == CgImpactFrame.Tone.LIGHT ? 1f : 0f)
                .vec4("_ImpactDraw", f.fillSubject() ? 1f : 0f, f.lines(), f.hatch(), f.star())
                .vec4("_ImpactMore", f.cross() ? 1f : 0f, f.jitter(), 0f, 0f)
                .vec4("_ImpactLight", f.lightR(), f.lightG(), f.lightB(), 1f)
                .vec4("_ImpactDark", f.darkR(), f.darkG(), f.darkB(), 1f)
                .vec4("_Focus", inputs.focusX, inputs.focusY, 0f, 0f)
                .vec4("_Fade", inputs.fadeR, inputs.fadeG, inputs.fadeB, inputs.fade);
        float[] g = inputs.grade;
        for (int i = 0; i < GRADE.length; i++) b.vec4(GRADE[i], g[i * 4], g[i * 4 + 1], g[i * 4 + 2], g[i * 4 + 3]);
        for (int k = 0; k < LAYER_SLOTS; k++) {
            if (inputs.layers[k] != null) b.sampler(LAYER[k], 0, inputs.layers[k]);
            if (inputs.layerBlooms[k] != null) b.sampler(LAYER_BLOOM[k], 0, inputs.layerBlooms[k]);
            if (inputs.layerLuts[k] != null) b.sampler(LAYER_LUT[k], 0, inputs.layerLuts[k]);
            b.vec4(LAYER_PARAMS[k], inputs.layers[k] != null ? 1f : 0f, inputs.layerBlooms[k] != null ? inputs.layerIntensity[k] : 0f,
                    inputs.layerLuts[k] != null ? 1f : 0f, 0f);
        }
    }

    /** What the composite's properties hold. */
    private static final class Inputs {
        CgGraphTexture scene, bloom, subject;
        CgTexture lut;
        float intensity = Float.NaN, r, g, b;
        float tonemapWeight = 1f;
        boolean conserve, filmic;
        float exposure = 1f, vignette, chromatic, impact, focusX = 0.5f, focusY = 0.5f;
        float fade, fadeR, fadeG, fadeB;
        CgImpactFrame frame = CgImpactFrame.NEGATIVE;
        int seed;
        final float[] grade = new float[GRADE.length * 4];
        final CgGraphTexture[] layers = new CgGraphTexture[LAYER_SLOTS], layerBlooms = new CgGraphTexture[LAYER_SLOTS];
        final CgTexture[] layerLuts = new CgTexture[LAYER_SLOTS];
        final float[] layerIntensity = new float[LAYER_SLOTS];

        boolean same(Inputs o) {
            return Arrays.equals(layers, o.layers) && Arrays.equals(layerBlooms, o.layerBlooms) && Arrays.equals(layerLuts, o.layerLuts)
                    && Arrays.equals(layerIntensity, o.layerIntensity) && scene == o.scene && bloom == o.bloom && subject == o.subject && lut == o.lut && intensity == o.intensity && r == o.r && g == o.g
                    && b == o.b && conserve == o.conserve && exposure == o.exposure && vignette == o.vignette
                    && tonemapWeight == o.tonemapWeight && filmic == o.filmic
                    && chromatic == o.chromatic && impact == o.impact && frame == o.frame && seed == o.seed
                    && focusX == o.focusX && focusY == o.focusY && fade == o.fade && fadeR == o.fadeR
                    && fadeG == o.fadeG && fadeB == o.fadeB && Arrays.equals(grade, o.grade);
        }

        void copyFrom(Inputs o) {
            scene = o.scene;
            bloom = o.bloom;
            subject = o.subject;
            lut = o.lut;
            frame = o.frame;
            seed = o.seed;
            intensity = o.intensity;
            r = o.r;
            g = o.g;
            b = o.b;
            conserve = o.conserve;
            tonemapWeight = o.tonemapWeight;
            filmic = o.filmic;
            exposure = o.exposure;
            vignette = o.vignette;
            chromatic = o.chromatic;
            impact = o.impact;
            focusX = o.focusX;
            focusY = o.focusY;
            fade = o.fade;
            fadeR = o.fadeR;
            fadeG = o.fadeG;
            fadeB = o.fadeB;
            System.arraycopy(o.grade, 0, grade, 0, grade.length);
            System.arraycopy(o.layers, 0, layers, 0, LAYER_SLOTS);
            System.arraycopy(o.layerBlooms, 0, layerBlooms, 0, LAYER_SLOTS);
            System.arraycopy(o.layerLuts, 0, layerLuts, 0, LAYER_SLOTS);
            System.arraycopy(o.layerIntensity, 0, layerIntensity, 0, LAYER_SLOTS);
        }

        void clearLayers() {
            Arrays.fill(layers, null);
            Arrays.fill(layerBlooms, null);
            Arrays.fill(layerLuts, null);
            Arrays.fill(layerIntensity, 0f);
        }
    }

    /** Clears this firing's inputs. The stack's, before its effects record. */
    public void begin() {
        active = 0;
        copy = false;
        inputs.scene = null;
        inputs.bloom = null;
        inputs.subject = null;
        inputs.lut = null;
        inputs.clearLayers();
    }

    /**
     * Composites from {@code scene}, the firing's linear HDR scene ({@code CgFrameKeys.SCENE}), into the target: the
     * pass then always draws, in the copy form, reading the scene rather than a copy of the target. The stack's.
     */
    public CgPostComposite scene(CgGraphTexture scene) {
        inputs.scene = scene;
        copy = true;
        return this;
    }

    /**
     * Lays {@code chain}'s level 0, the whole glow, tinted {@code (r, g, b)}, over the target: added times
     * {@code intensity}, or with {@code conserve} mixed in at that share (0 to 1), the picture dimmed to make room.
     */
    public CgPostComposite bloom(CgGraphTexture chain, float intensity, float r, float g, float b, boolean conserve) {
        inputs.bloom = chain;
        inputs.intensity = intensity;
        inputs.r = r;
        inputs.g = g;
        inputs.b = b;
        inputs.conserve = conserve;
        return on(CgCompositeFeature.BLOOM);
    }

    /** Multiplies the picture, in linear light, by {@code exposure}: 2 doubles it. Bloom is added after, unscaled. */
    public CgPostComposite flash(float exposure) {
        inputs.exposure = exposure;
        return on(CgCompositeFeature.FLASH);
    }

    /** Darkens the corners by {@code amount}, 0 to 1. */
    public CgPostComposite vignette(float amount) {
        inputs.vignette = amount;
        return on(CgCompositeFeature.VIGNETTE);
    }

    /** Unreal's colour fringe, {@code amount} percent, toward {@code (x, y)}, 0 to 1 from the bottom left. */
    public CgPostComposite chromatic(float amount, float x, float y) {
        inputs.chromatic = amount;
        inputs.focusX = x;
        inputs.focusY = y;
        return on(CgCompositeFeature.CHROMATIC);
    }

    /** Turns the picture {@code amount} (0 to 1) into preset {@code look}; focus lines radiate from {@code (x, y)}. */
    public CgPostComposite impact(CgImpact look, float amount, float x, float y) {
        return impact(look.frame(), amount, 0, x, y, null);
    }

    /**
     * Turns the picture {@code amount} (0 to 1) into {@code look} drawn from {@code seed}, centred on {@code (x, y)};
     * a drawn frame's subject is {@code subject} ({@code CgFrameKeys.SUBJECT}, bent), or with none the picture's light
     * past white.
     */
    public CgPostComposite impact(CgImpactFrame look, float amount, int seed, float x, float y, @Nullable CgGraphTexture subject) {
        inputs.impact = amount;
        inputs.frame = look;
        inputs.seed = seed;
        inputs.focusX = x;
        inputs.focusY = y;
        inputs.subject = subject;
        return on(CgCompositeFeature.IMPACT);
    }

    /**
     * Fades the picture {@code amount} (0 to 1) toward {@code (r, g, b)}, in the target's encoding, after every other
     * look: drawn by either form.
     */
    public CgPostComposite fade(float amount, float r, float g, float b) {
        inputs.fade = amount;
        inputs.fadeR = r;
        inputs.fadeG = g;
        inputs.fadeB = b;
        return on(CgCompositeFeature.FADE);
    }

    /**
     * Tonemaps the scene as Unreal's tonemap pass does: 4.21's (bloom added, then the exposure) or with {@code filmic}
     * 5.1's (the exposure, then bloom added), then the vignette, then {@code lut} ({@code CgFilmLut}) read through the
     * log shaper, already in the target's encoding; mixed {@code weight} (0 to 1) of the way from the roll to white.
     * Wants the HDR scene ({@link #scene}).
     */
    public CgPostComposite tonemap(CgTexture lut, float weight, boolean filmic) {
        inputs.lut = lut;
        inputs.tonemapWeight = Math.max(0f, Math.min(1f, weight));
        inputs.filmic = filmic;
        return on(CgCompositeFeature.TONEMAP);
    }

    /**
     * Grades the picture by {@code constants} ({@code CgColorGrade.constants}): Unreal's white balance and colour
     * grading in ACEScg, before the roll to white, and its scene tint after. With the tonemapper on, its table holds
     * the grade instead.
     */
    public CgPostComposite grade(float[] constants) {
        System.arraycopy(constants, 0, inputs.grade, 0, inputs.grade.length);
        return on(CgCompositeFeature.GRADE);
    }

    /**
     * Lays graded layer {@code k}'s light ({@code CgGradeLayers}) over the frame's look as its own look tones it: by
     * {@code lut} ({@code CgFilmLut}), or with none by the roll to white, adding only what it contributes. Wants the HDR
     * scene ({@link #scene}). Its bloom is {@link #layerBloom}.
     *
     * <pre>{@code
     * post.composite().layer(k, layers.layer(k), lut.texture(post.recording(), layers.look(k)));   // at BEFORE_COMPOSITE
     * }</pre>
     */
    public CgPostComposite layer(int k, CgGraphTexture layer, @Nullable CgTexture lut) {
        inputs.layers[k] = layer;
        inputs.layerLuts[k] = lut;
        return on(CgCompositeFeature.LAYERS);
    }

    /** Graded layer {@code k}'s own bloom, {@code glow} times {@code intensity}, toned with its light ({@link #layer}). */
    public CgPostComposite layerBloom(int k, CgGraphTexture glow, float intensity) {
        inputs.layerBlooms[k] = glow;
        inputs.layerIntensity[k] = intensity;
        return this;
    }

    /** Composites this firing in linear light ({@link CgCompositeForm#COPY}), at the cost of a copy of the target. */
    public CgPostComposite linear() {
        copy = true;
        return this;
    }

    private CgPostComposite on(CgCompositeFeature feature) {
        active |= 1 << feature.ordinal();
        if (!feature.blend) copy = true;
        return this;
    }

    /** Whether an effect has switched {@code feature} on this firing. */
    public boolean active(CgCompositeFeature feature) {
        return (active & 1 << feature.ordinal()) != 0;
    }

    /** The form this firing will draw in. */
    public CgCompositeForm form() {
        return copy ? CgCompositeForm.COPY : CgCompositeForm.BLEND;
    }

    /** Records the pass onto {@code target} if any feature is on. The stack's. */
    public void record(CgRecording recording, CgGraphTexture target, CgPassConstants constants) {
        boolean fromScene = inputs.scene != null;
        if (active == 0 && !fromScene) return;
        CgCompositeForm form = form();
        int f = form.ordinal();
        CgMaterialInstances forms = instances[f];
        if (forms == null) forms = instances[f] = new CgMaterialInstances(CgMaterial.load(form.shader));
        boolean subject = active(CgCompositeFeature.IMPACT) && inputs.subject != null;
        int keys = active | (fromScene ? SCENE_KEY : 0) | (subject ? SUBJECT_KEY : 0);
        boolean switched = false;
        if (keys != keyed[f]) {
            CgMaterial variant = forms.material();
            for (CgCompositeFeature feature : FEATURES) {
                if (form == CgCompositeForm.COPY || feature.blend) variant = variant.keyword(feature.keyword, active(feature));
            }
            if (form == CgCompositeForm.COPY) variant = variant.keyword("SCENE", fromScene).keyword("SUBJECT", subject);
            CgMaterialInstance chosen = forms.of(variant);
            switched = chosen != current[f];
            current[f] = chosen;
            keyed[f] = keys;
        }
        CgMaterialInstance material = current[f];
        // Each variant's instance holds its own values: one chosen anew is written whatever the inputs.
        if (switched || !inputs.same(set[f])) {
            write(material);
            set[f].copyFrom(inputs);
        }
        CgPipeline pipeline = CgMaterialProxy.of(material).pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass pass = recording.raster("post.composite").color(target, CgLoad.load()).constants(constants).timed(GPU);
        if (form == CgCompositeForm.COPY && !fromScene) pass.readsColor(target);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, CgMaterialProxy.of(material).captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    /** Forgets its materials, which the material registry frees with the context. */
    public void release() {
        for (int f = 0; f < FORMS.length; f++) {
            instances[f] = null;
            current[f] = null;
            keyed[f] = -1;
            set[f].scene = null;
            set[f].bloom = null;
            set[f].subject = null;
            set[f].lut = null;
            set[f].clearLayers();
            set[f].intensity = Float.NaN;
        }
    }
}
