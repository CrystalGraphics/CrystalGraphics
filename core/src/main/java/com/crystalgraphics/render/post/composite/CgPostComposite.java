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
import com.crystalgraphics.trace.CgGpuTrace;

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
 * }</pre>
 *
 * <ul>
 *   <li>Each input switches on a {@link CgCompositeFeature}; a firing with none records nothing.</li>
 *   <li>The form ({@link CgCompositeForm}) is {@code BLEND} unless an input asks for {@code COPY}.</li>
 *   <li>Inputs last one firing: the stack clears them before its effects record.</li>
 * </ul>
 */
public final class CgPostComposite {

    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU = CgGpuTrace.name("post.composite");
    private static final CgCompositeFeature[] FEATURES = CgCompositeFeature.values();
    private static final CgCompositeForm[] FORMS = CgCompositeForm.values();

    /** Per form: its material, the features its keywords are set to, and what its properties were last set to. */
    private final CgMaterial[] materials = new CgMaterial[FORMS.length];
    private final int[] keyed = {-1, -1};
    private final Inputs[] set = {new Inputs(), new Inputs()};

    /** This firing's inputs, as bits by feature ordinal, and whether it asked for linear light. */
    private int active;
    private boolean copy;
    private final Inputs inputs = new Inputs();
    private final Consumer<CgShaderBindings> properties = b -> b.sampler("_Bloom", 0, inputs.bloom)
            .set1f("_Intensity", inputs.intensity).vec4("_Tint", inputs.r, inputs.g, inputs.b, 1f)
            .set1f("_Conserve", inputs.conserve ? 1f : 0f);

    /** What the composite's properties hold. */
    private static final class Inputs {
        CgGraphTexture bloom;
        float intensity = Float.NaN, r, g, b;
        boolean conserve;

        boolean same(Inputs o) {
            return bloom == o.bloom && intensity == o.intensity && r == o.r && g == o.g && b == o.b && conserve == o.conserve;
        }

        void copyFrom(Inputs o) {
            bloom = o.bloom;
            intensity = o.intensity;
            r = o.r;
            g = o.g;
            b = o.b;
            conserve = o.conserve;
        }
    }

    /** Clears this firing's inputs. The stack's, before its effects record. */
    public void begin() {
        active = 0;
        copy = false;
        inputs.bloom = null;
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
        active |= 1 << CgCompositeFeature.BLOOM.ordinal();
        return this;
    }

    /** Composites this firing in linear light ({@link CgCompositeForm#COPY}), at the cost of a copy of the target. */
    public CgPostComposite linear() {
        copy = true;
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
        if (active == 0) return;
        CgCompositeForm form = form();
        int f = form.ordinal();
        CgMaterial material = materials[f];
        if (material == null) material = materials[f] = CgMaterial.newInstance(form.shader);
        if (active != keyed[f]) {
            for (CgCompositeFeature feature : FEATURES) material.toggleKeyword(feature.keyword, active(feature));
            keyed[f] = active;
        }
        if (!inputs.same(set[f])) {
            material.applyProperties(properties);
            set[f].copyFrom(inputs);
        }
        CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass pass = recording.raster(target, CgLoad.load(), constants, null, CgOrder.SORTED).timed(GPU);
        if (form == CgCompositeForm.COPY) pass.sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT);
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
            set[f].bloom = null;
            set[f].intensity = Float.NaN;
        }
    }
}
