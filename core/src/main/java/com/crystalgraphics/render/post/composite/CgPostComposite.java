package com.crystalgraphics.render.post.composite;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
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
import java.util.function.Consumer;

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
    /** The keyword set's bit for {@code SCENE}, above every feature's. */
    private static final int SCENE_KEY = 1 << 30, SUBJECT_KEY = 1 << 29;

    /** Per form: its material, the features its keywords are set to, and what its properties were last set to. */
    private final CgMaterial[] materials = new CgMaterial[FORMS.length];
    private final int[] keyed = {-1, -1};
    private final Inputs[] set = {new Inputs(), new Inputs()};

    /** This firing's inputs, as bits by feature ordinal, and whether it asked for linear light. */
    private int active;
    private boolean copy;
    private final Inputs inputs = new Inputs();
    private final Consumer<CgShaderBindings> properties = b -> {
        if (inputs.scene != null) b.sampler("_Scene", 0, inputs.scene);
        if (inputs.bloom != null) b.sampler("_Bloom", 0, inputs.bloom);   // a look without bloom leaves it unread
        if (inputs.subject != null) b.sampler("_Subject", 0, inputs.subject);
        CgImpactFrame f = inputs.frame;
        b.set1f("_Intensity", inputs.intensity).vec4("_Tint", inputs.r, inputs.g, inputs.b, 1f)
                .set1f("_Conserve", inputs.conserve ? 1f : 0f).set1f("_Exposure", inputs.exposure)
                .set1f("_Vignette", inputs.vignette).set1f("_Chromatic", inputs.chromatic)
                .vec4("_Impact", inputs.impact, f.look(), inputs.seed, f.paper() == CgImpactFrame.Tone.LIGHT ? 1f : 0f)
                .vec4("_ImpactDraw", f.fillSubject() ? 1f : 0f, f.lines(), f.hatch(), f.star())
                .vec4("_ImpactMore", f.cross() ? 1f : 0f, f.jitter(), 0f, 0f)
                .vec4("_ImpactLight", f.lightR(), f.lightG(), f.lightB(), 1f)
                .vec4("_ImpactDark", f.darkR(), f.darkG(), f.darkB(), 1f)
                .vec4("_Focus", inputs.focusX, inputs.focusY, 0f, 0f);
    };

    /** What the composite's properties hold. */
    private static final class Inputs {
        CgGraphTexture scene, bloom, subject;
        float intensity = Float.NaN, r, g, b;
        boolean conserve;
        float exposure = 1f, vignette, chromatic, impact, focusX = 0.5f, focusY = 0.5f;
        CgImpactFrame frame = CgImpactFrame.NEGATIVE;
        int seed;

        boolean same(Inputs o) {
            return scene == o.scene && bloom == o.bloom && subject == o.subject && intensity == o.intensity && r == o.r && g == o.g
                    && b == o.b && conserve == o.conserve && exposure == o.exposure && vignette == o.vignette
                    && chromatic == o.chromatic && impact == o.impact && frame == o.frame && seed == o.seed
                    && focusX == o.focusX && focusY == o.focusY;
        }

        void copyFrom(Inputs o) {
            scene = o.scene;
            bloom = o.bloom;
            subject = o.subject;
            frame = o.frame;
            seed = o.seed;
            intensity = o.intensity;
            r = o.r;
            g = o.g;
            b = o.b;
            conserve = o.conserve;
            exposure = o.exposure;
            vignette = o.vignette;
            chromatic = o.chromatic;
            impact = o.impact;
            focusX = o.focusX;
            focusY = o.focusY;
        }
    }

    /** Clears this firing's inputs. The stack's, before its effects record. */
    public void begin() {
        active = 0;
        copy = false;
        inputs.scene = null;
        inputs.bloom = null;
        inputs.subject = null;
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

    /** Splits red and blue apart by {@code amount} (0 to 1) from {@code (x, y)}, 0 to 1 from the bottom left. */
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
        CgMaterial material = materials[f];
        if (material == null) material = materials[f] = CgMaterial.newInstance(form.shader);
        boolean subject = active(CgCompositeFeature.IMPACT) && inputs.subject != null;
        int keys = active | (fromScene ? SCENE_KEY : 0) | (subject ? SUBJECT_KEY : 0);
        if (keys != keyed[f]) {
            for (CgCompositeFeature feature : FEATURES) {
                if (form == CgCompositeForm.COPY || feature.blend) material.toggleKeyword(feature.keyword, active(feature));
            }
            if (form == CgCompositeForm.COPY) {
                material.toggleKeyword("SCENE", fromScene);
                material.toggleKeyword("SUBJECT", subject);
            }
            keyed[f] = keys;
        }
        if (!inputs.same(set[f])) {
            material.applyProperties(properties);
            set[f].copyFrom(inputs);
        }
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass pass = recording.raster(target, CgLoad.load(), constants, null, CgOrder.SORTED).timed(GPU);
        if (form == CgCompositeForm.COPY && !fromScene) pass.sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT);
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, material.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }

    /** Forgets its materials, which the material registry frees with the context. */
    public void release() {
        for (int f = 0; f < FORMS.length; f++) {
            materials[f] = null;
            keyed[f] = -1;
            set[f].scene = null;
            set[f].bloom = null;
            set[f].subject = null;
            set[f].intensity = Float.NaN;
        }
    }
}
